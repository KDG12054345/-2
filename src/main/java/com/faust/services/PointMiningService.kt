package com.faust.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.R
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.models.PointTransaction
import com.faust.models.TransactionType
import com.faust.presentation.view.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * [시스템 진입점: 백그라운드 유지 진입점]
 * 
 * 역할: Foreground Service로 실행되어 앱이 꺼져 있어도 포인트 채굴 로직이 지속되도록 보장하는 지점입니다.
 * 트리거: MainActivity.startServices() 호출 또는 PointMiningService.startService(context) 호출
 * 처리: 1분마다 포인트 자동 적립 (이벤트 기반 아키텍처로 전환)
 * 
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class PointMiningService : LifecycleService() {
    private val database: FaustDatabase by lazy {
        (application as FaustApplication).database
    }
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(this)
    }
    private var miningJob: Job? = null
    private var audioMonitoringJob: Job? = null  // 화면 OFF 시 오디오 모니터링 Job
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var screenEventReceiver: BroadcastReceiver? = null
    
    // 상태 관리 변수
    private var isScreenOn = true
    private var isMiningPaused = false

    companion object {
        private const val TAG = "PointMiningService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "point_mining_channel"
        
        @Volatile private var instance: PointMiningService? = null

        fun startService(context: Context) {
            val intent = Intent(context, PointMiningService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PointMiningService::class.java)
            context.stopService(intent)
        }
        
        /**
         * 외부에서 포인트 적립을 일시 중단합니다.
         */
        fun pauseMining() {
            instance?.let {
                it.isMiningPaused = true
                Log.d(TAG, "Mining paused via external signal")
            }
        }
        
        /**
         * 외부에서 포인트 적립을 재개합니다.
         */
        fun resumeMining() {
            instance?.let {
                it.isMiningPaused = false
                Log.d(TAG, "Mining resumed via external signal")
            }
        }
        
        /**
         * 현재 포인트 적립이 일시 중단되었는지 확인합니다.
         */
        fun isMiningPaused(): Boolean {
            return instance?.isMiningPaused ?: false
        }

        /**
         * 사용자가 '강행'을 선택했을 때 단 한 번 벌금을 부과합니다.
         * @param context Context (ApplicationContext 권장)
         * @param penaltyAmount 벌금 액수 (예: 10)
         */
        fun applyOneTimePenalty(context: Context, penaltyAmount: Int) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    val database = (context.applicationContext as FaustApplication).database
                    val preferenceManager = PreferenceManager(context)
                    
                    if (penaltyAmount <= 0) return@launch
                    
                    Log.w(TAG, "사용자 강행 선택: 벌금 ${penaltyAmount}WP 부과")
                    
                    val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0
                    val actualPenalty = penaltyAmount.coerceAtMost(currentPoints)
                    
                    database.withTransaction {
                        database.pointTransactionDao().insertTransaction(
                            PointTransaction(
                                amount = -actualPenalty,
                                type = TransactionType.PENALTY,
                                reason = "차단 앱 강행 사용으로 인한 벌점"
                            )
                        )
                    }
                    // UI 동기화를 위해 현재 포인트 갱신
                    val newPoints = (currentPoints - actualPenalty).coerceAtLeast(0)
                    preferenceManager.setCurrentPoints(newPoints)
                    
                    Log.w(TAG, "강행 포인트 차감 완료: ${actualPenalty} WP 차감 (기존: ${currentPoints} WP → 현재: ${newPoints} WP)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply one-time penalty", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Foreground Service 시작 (앱이 종료되어도 죽지 않음)
        startForeground(NOTIFICATION_ID, createNotification())
        preferenceManager.setServiceRunning(true)
        
        // 화면 이벤트 리시버 등록
        registerScreenEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        Log.d(TAG, "Mining Service Started")
        
        // 실제 화면 상태 확인 및 초기화
        checkAndUpdateScreenState()
        
        startMiningJob()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        miningJob?.cancel()
        audioMonitoringJob?.cancel()  // 오디오 모니터링 Job도 취소
        serviceScope.cancel()
        unregisterScreenEventReceiver()
        preferenceManager.setServiceRunning(false)
        Log.d(TAG, "Mining Service Stopped")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * 실제 화면 상태를 확인하고 isScreenOn 변수를 업데이트합니다.
     */
    private fun checkAndUpdateScreenState() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wasScreenOn = isScreenOn
            isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            }
            
            if (wasScreenOn != isScreenOn) {
                Log.d(TAG, "화면 상태 확인: ${if (isScreenOn) "ON" else "OFF"} (이전: ${if (wasScreenOn) "ON" else "OFF"})")
            } else {
                Log.d(TAG, "화면 상태 확인: ${if (isScreenOn) "ON" else "OFF"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "화면 상태 확인 실패, 기본값 사용", e)
            // 기본값은 이미 true로 설정되어 있음
        }
    }

    /**
     * 단순 타이머: 1분마다 포인트를 적립합니다.
     * 화면이 켜져있고, 포인트 적립이 일시 중단되지 않았을 때만 작동합니다.
     */
    private fun startMiningJob() {
        miningJob?.cancel()
        miningJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(60_000L) // 1분 대기
                    if (isScreenOn && !isMiningPaused) {
                        addMiningPoints(1)
                        Log.d(TAG, "포인트 적립: 1 WP (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isMiningPaused)")
                    } else {
                        Log.d(TAG, "포인트 적립 스킵 (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isMiningPaused)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in mining loop", e)
                }
            }
        }
        Log.d(TAG, "Mining Job Started (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isMiningPaused)")
    }


    private suspend fun addMiningPoints(points: Int) {
        if (points <= 0) return
        try {
            // DB 트랜잭션 처리
            database.withTransaction {
                database.pointTransactionDao().insertTransaction(
                    PointTransaction(
                        amount = points,
                        type = TransactionType.MINING,
                        reason = "앱 사용 시간 채굴"
                    )
                )
            }
            // 트랜잭션 성공 후 UI 동기화
            val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0
            preferenceManager.setCurrentPoints(currentPoints.coerceAtLeast(0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add points", e)
        }
    }

    /**
     * 차단 앱 사용으로 인한 포인트 차감 함수
     * 손실 회피 심리를 활용하여 사용자가 차단 앱을 사용하지 않도록 동기부여를 제공합니다.
     */
    private suspend fun subtractPoints(points: Int) {
        if (points <= 0) return
        try {
            database.withTransaction {
                database.pointTransactionDao().insertTransaction(
                    PointTransaction(
                        amount = -points, // 음수 값으로 저장
                        type = TransactionType.PENALTY, // 'MINING' 대신 'PENALTY' 타입 사용
                        reason = "차단 앱 사용으로 인한 벌점"
                    )
                )
            }
            // UI 동기화를 위해 현재 포인트 갱신
            val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0
            preferenceManager.setCurrentPoints(currentPoints.coerceAtLeast(0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subtract points", e)
        }
    }


    /**
     * 화면 이벤트 리시버를 등록합니다.
     * ACTION_SCREEN_ON과 ACTION_SCREEN_OFF 이벤트를 감지합니다.
     */
    private fun registerScreenEventReceiver() {
        screenEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        Log.d(TAG, "Screen ON: 정산 시작 및 타이머 재개")
                        // 오디오 모니터링 중지
                        audioMonitoringJob?.cancel()
                        audioMonitoringJob = null
                        // 1. 화면이 꺼져있던 동안의 포인트 일괄 계산 로직 실행
                        serviceScope.launch {
                            calculateAccumulatedPoints()
                        }
                        // 2. 타이머 다시 시작
                        startMiningJob()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        Log.d(TAG, "Screen OFF: 타이머 중지 및 절전 모드")
                        // 타이머 중지 (Coroutine Job cancel)
                        miningJob?.cancel()
                        miningJob = null
                        // 화면이 꺼진 시간 저장 (보너스 계산 기준점)
                        preferenceManager.setLastScreenOffTime(System.currentTimeMillis())
                        // 오디오 모니터링 시작 (차단 앱 음성 감지)
                        startAudioMonitoring()
                        // 주의: isMiningPaused는 절대 변경하지 않음
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenEventReceiver, filter)
        Log.d(TAG, "Screen Event Receiver Registered")
    }

    /**
     * 화면 이벤트 리시버를 해제합니다.
     */
    private fun unregisterScreenEventReceiver() {
        screenEventReceiver?.let {
            try {
                unregisterReceiver(it)
                screenEventReceiver = null
                Log.d(TAG, "Screen Event Receiver Unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen event receiver", e)
            }
        }
    }

    /**
     * 화면 OFF 상태에서 차단 앱의 오디오 출력을 감지합니다.
     * 차단 앱에서 음성이 출력되면 포인트 채굴을 중단합니다.
     */
    private fun startAudioMonitoring() {
        audioMonitoringJob?.cancel()
        audioMonitoringJob = serviceScope.launch {
            while (isActive && !isScreenOn) {
                try {
                    delay(10_000L) // 10초마다 확인
                    
                    if (isScreenOn) {
                        // 화면이 켜졌으면 모니터링 중지
                        break
                    }
                    
                    // 차단 앱에서 오디오가 재생 중인지 확인
                    val hasBlockedAppAudio = checkBlockedAppAudio()
                    
                    if (hasBlockedAppAudio && !isMiningPaused) {
                        // 차단 앱에서 오디오 재생 중이면 포인트 채굴 일시정지
                        isMiningPaused = true
                        Log.w(TAG, "차단 앱 오디오 감지: 포인트 채굴 일시정지")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio monitoring loop", e)
                }
            }
        }
        Log.d(TAG, "Audio Monitoring Started")
    }

    /**
     * 현재 오디오를 재생하는 앱이 차단 앱 목록에 있는지 확인합니다.
     * 
     * 주의: Android의 개인정보 보호 정책으로 인해 AudioPlaybackConfiguration에서
     * 직접 패키지명을 가져올 수 없습니다. 따라서 추정(Heuristic) 방식을 사용합니다.
     * 
     * @return 차단 앱에서 오디오가 재생 중인 것으로 추정되면 true
     */
    private suspend fun checkBlockedAppAudio(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // 1. 현재 오디오가 재생 중인지 확인
            if (!audioManager.isMusicActive) {
                return false
            }
            
            // 2. 마지막으로 감지된 앱이 차단 목록에 있었는지 확인
            // PreferenceManager에 저장된 마지막 앱 정보를 활용합니다.
            val lastApp = preferenceManager.getLastMiningApp()
            if (lastApp != null) {
                val isBlocked = withContext(Dispatchers.IO) {
                    database.appBlockDao().getBlockedApp(lastApp) != null
                }
                
                if (isBlocked) {
                    Log.d(TAG, "차단 앱($lastApp)에서 오디오 재생 중인 것으로 추정됨")
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check blocked app audio", e)
            false
        }
    }

    /**
     * 화면이 꺼져있던 동안의 포인트를 일괄 계산합니다.
     * 보안 로직을 통해 꼼수를 차단합니다.
     */
    private suspend fun calculateAccumulatedPoints() {
        // 1. 차단 앱을 켜둔 채 화면을 끈 경우 (정산 제외)
        if (isMiningPaused) {
            Log.d(TAG, "차단 앱 사용 중 화면 OFF -> 정산 제외")
            return
        }

        // 2. 차단 앱 오디오 감지 (화면 OFF 중 차단 앱에서 음성 출력)
        if (checkBlockedAppAudio()) {
            Log.d(TAG, "차단 앱 오디오 재생 감지 -> 정산 제외")
            return
        }

        val startTime = preferenceManager.getLastScreenOffTime()
        val endTime = System.currentTimeMillis()

        // 시작 시간이 0이면 (첫 실행 등) 스킵
        if (startTime == 0L) {
            Log.d(TAG, "calculateAccumulatedPoints: No previous screen off time, skipping")
            return
        }

        // 화면이 꺼진 시간부터 현재까지의 시간(분) 계산
        val offDurationMinutes = ((endTime - startTime) / (1000 * 60)).toInt()

        if (offDurationMinutes > 0) {
            // 휴대폰을 꺼두고 유혹을 참은 시간만큼 보너스 포인트 지급!
            addMiningPoints(offDurationMinutes)
            Log.d(TAG, "부재 중 디톡스 성공: ${offDurationMinutes}포인트 일괄 지급 🎁")
        } else {
            Log.d(TAG, "calculateAccumulatedPoints: No duration to calculate")
        }
        
        // 정산 후에는 반드시 시간 리셋
        preferenceManager.setLastScreenOnTime(endTime)
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_point_mining),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "포인트 채굴 서비스"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_point_mining_title))
            .setContentText("열심히 포인트를 채굴하고 있어요 ⛏️")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
