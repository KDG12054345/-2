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
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.PackageManager
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
    // audioMonitoringJob 제거 (더 이상 필요 없음 - 이벤트 기반으로 전환)
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var screenEventReceiver: BroadcastReceiver? = null
    
    // 상태 관리 변수
    private var isScreenOn = true
    private var isPausedByApp = false      // 앱 실행으로 인한 일시정지 (시각적 차단)
    private var isPausedByAudio = false    // 오디오로 인한 일시정지 (청각적 차단)
    
    // ⚠️ 핵심: 계산된 속성으로 OR 조건 보장 (반드시 getter로 구현)
    // 절대 private var isMiningPaused = false 같은 직접 변수로 선언하지 말 것!
    private val isMiningPaused: Boolean
        get() = isPausedByApp || isPausedByAudio  // 하나라도 true면 채굴 중단

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
         * 시각적 차단 상태만 관리 (isPausedByApp)
         */
        fun pauseMining() {
            instance?.updateVisualBlockState(true)
        }
        
        /**
         * 외부에서 포인트 적립을 재개합니다.
         * 시각적 차단 상태만 관리 (isPausedByApp)
         */
        fun resumeMining() {
            instance?.updateVisualBlockState(false)
        }
        
        /**
         * 현재 포인트 적립이 일시 중단되었는지 확인합니다.
         * 계산된 속성 반환 (isPausedByApp || isPausedByAudio)
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
                    
                    val currentPoints = (database.pointTransactionDao().getTotalPoints() ?: 0).coerceAtLeast(0)
                    // 포인트가 0 미만이 되지 않도록 패널티 제한
                    // 예: 현재 포인트 1, 요청 패널티 6 → 실제 차감 1, 결과 포인트 0
                    // 예: 현재 포인트 4, 요청 패널티 6 → 실제 차감 4, 결과 포인트 0
                    val actualPenalty = penaltyAmount.coerceAtMost(currentPoints)
                    
                    if (actualPenalty <= 0) {
                        Log.d(TAG, "포인트 부족으로 패널티 적용 불가: 요청 ${penaltyAmount} WP, 현재 ${currentPoints} WP")
                        return@launch
                    }
                    
                    database.withTransaction {
                        database.pointTransactionDao().insertTransaction(
                            PointTransaction(
                                amount = -actualPenalty,
                                type = TransactionType.PENALTY,
                                reason = "차단 앱 강행 사용으로 인한 벌점"
                            )
                        )
                    }
                    // UI 동기화를 위해 현재 포인트 갱신 (항상 0 이상)
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
        // 오디오 감시 시작 (화면 상태와 무관하게 작동)
        startAudioMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        miningJob?.cancel()
        stopAudioMonitoring()  // ⚠️ 반드시 콜백 해제 (메모리 누수 방지) - 기존: audioMonitoringJob?.cancel()
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
     * 시각적 차단 상태를 업데이트합니다.
     * 오디오 상태는 건드리지 않고 isPausedByApp만 관리합니다.
     */
    private fun updateVisualBlockState(isBlocked: Boolean) {
        isPausedByApp = isBlocked
        Log.d(TAG, "Visual block state updated: isPausedByApp=$isBlocked, isMiningPaused=$isMiningPaused")
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
                    // ⚠️ 핵심: isMiningPaused는 계산된 속성(getter)을 사용하므로 OR 조건이 자동 적용됨
                    if (isScreenOn && !isMiningPaused) {  // isMiningPaused = isPausedByApp || isPausedByAudio
                        addMiningPoints(1)
                        Log.d(TAG, "포인트 적립: 1 WP (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isMiningPaused [App: $isPausedByApp, Audio: $isPausedByAudio])")
                    } else {
                        Log.d(TAG, "포인트 적립 스킵 (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isMiningPaused [App: $isPausedByApp, Audio: $isPausedByAudio])")
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
            // 트랜잭션 성공 후 UI 동기화 (항상 0 이상 보장)
            val currentPoints = (database.pointTransactionDao().getTotalPoints() ?: 0).coerceAtLeast(0)
            preferenceManager.setCurrentPoints(currentPoints)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add points", e)
        }
    }

    /**
     * 차단 앱 사용으로 인한 포인트 차감 함수
     * 손실 회피 심리를 활용하여 사용자가 차단 앱을 사용하지 않도록 동기부여를 제공합니다.
     * 포인트가 0 미만이 되지 않도록 보장합니다.
     */
    private suspend fun subtractPoints(points: Int) {
        if (points <= 0) return
        try {
            database.withTransaction {
                // 현재 포인트 조회 (0 이상 보장)
                val currentPoints = (database.pointTransactionDao().getTotalPoints() ?: 0).coerceAtLeast(0)
                
                // 포인트가 0 미만이 되지 않도록 차감량 제한
                val actualSubtraction = points.coerceAtMost(currentPoints)
                
                if (actualSubtraction <= 0) {
                    Log.d(TAG, "포인트 부족으로 차감 불가: 요청 ${points} WP, 현재 ${currentPoints} WP")
                    return@withTransaction
                }
                
                database.pointTransactionDao().insertTransaction(
                    PointTransaction(
                        amount = -actualSubtraction, // 음수 값으로 저장
                        type = TransactionType.PENALTY, // 'MINING' 대신 'PENALTY' 타입 사용
                        reason = "차단 앱 사용으로 인한 벌점"
                    )
                )
                
                // UI 동기화를 위해 현재 포인트 갱신 (항상 0 이상)
                val newPoints = (currentPoints - actualSubtraction).coerceAtLeast(0)
                preferenceManager.setCurrentPoints(newPoints)
                
                Log.d(TAG, "포인트 차감 완료: ${actualSubtraction} WP 차감 (기존: ${currentPoints} WP → 현재: ${newPoints} WP)")
            }
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
                        // 오디오 모니터링은 계속 실행 (중지하지 않음)
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
                        // 오디오 모니터링은 계속 실행 (이미 실행 중이면 재시작하지 않음)
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

    // AudioPlaybackCallback 구현 (API 26+) - 🟠 ANR 방지 필수
    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            super.onPlaybackConfigChanged(configs)
            Log.d(TAG, "오디오 콜백 호출: ${configs.size}개 세션 감지")
            // ⚠️ 핵심: 메인 스레드를 즉시 반환하고 백그라운드에서 처리 (ANR 방지)
            // 콜백은 메인 스레드에서 실행되므로 무거운 작업(DB 접근)은 코루틴으로 처리
            serviceScope.launch {
                handleAudioConfigChange(configs)
            }
        }
    }
    
    /**
     * 오디오 상태 변경 핸들러 (⚠️ 핵심: 실시간으로 차단 앱 검사, 🟠 ANR 방지)
     * ⚠️ 중요: 이 함수는 serviceScope.launch 내부에서 호출되므로 백그라운드 스레드에서 실행됨
     */
    private suspend fun handleAudioConfigChange(configs: List<AudioPlaybackConfiguration>) {
        Log.d(TAG, "오디오 상태 변경 처리 시작: ${configs.size}개 세션")
        // ⚠️ 핵심: 콜백에서 받은 configs를 즉시 차단 앱 목록과 비교
        // 오디오 재생 중인 모든 앱의 패키지명을 추출하여 차단 목록과 비교
        // 🟠 ANR 방지: suspend 함수로 백그라운드에서 DB 접근
        val hasBlockedAppAudio = checkBlockedAppAudioFromConfigs(configs)
        
        // ⚠️ 핵심: isPausedByAudio만 관리, isPausedByApp은 절대 건드리지 않음
        if (hasBlockedAppAudio) {
            if (!isPausedByAudio) {
                isPausedByAudio = true
                Log.w(TAG, "차단 앱 오디오 감지: 오디오 일시정지 플래그 설정 (isPausedByAudio=true)")
            }
        } else {
            // 오디오가 꺼졌거나 차단 앱이 아닌 경우, 오디오로 인한 일시정지만 해제
            // ⚠️ 핵심: isPausedByAudio만 false로 변경, isPausedByApp은 그대로 유지
            // configs가 비어있으면 모든 오디오가 꺼진 상태이므로 isPausedByAudio 해제
            if (isPausedByAudio) {
                isPausedByAudio = false
                Log.i(TAG, "차단 앱 오디오 종료: 오디오 일시정지 플래그 해제 (isPausedByAudio=false, isPausedByApp=${isPausedByApp}, isMiningPaused=${isMiningPaused})")
            }
        }
    }
    
    /**
     * 차단 앱의 오디오 출력을 감지합니다 (이벤트 기반, 화면 상태와 무관하게 작동).
     * AudioPlaybackCallback을 등록하여 오디오 상태 변경 시 즉시 감지합니다.
     */
    private fun startAudioMonitoring() {
        // API 26+ 체크 (AudioPlaybackCallback은 API 26+에서만 사용 가능)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "오디오 감시 미지원: API 레벨 ${Build.VERSION.SDK_INT} (최소 API 26 필요)")
            return
        }
        
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // 메인 스레드 또는 핸들러를 사용하여 콜백 등록
            audioManager.registerAudioPlaybackCallback(audioPlaybackCallback, null)
            Log.d(TAG, "Audio Playback Callback registered (event-driven, API ${Build.VERSION.SDK_INT})")
            
            // 콜백 등록 후 초기 오디오 상태 확인
            serviceScope.launch {
                try {
                    // API 29+에서는 activePlaybackConfigurations 사용, 그 외에는 isMusicActive로 확인
                    val currentConfigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        audioManager.activePlaybackConfigurations
                    } else {
                        // API 26-28: 콜백이 자동으로 호출되므로 여기서는 로그만 출력
                        emptyList()
                    }
                    Log.d(TAG, "초기 오디오 상태 확인: ${currentConfigs.size}개 활성 세션 (API ${Build.VERSION.SDK_INT})")
                    if (currentConfigs.isNotEmpty()) {
                        handleAudioConfigChange(currentConfigs)
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        // API 26-28: isMusicActive로 초기 상태 확인
                        val isMusicActive = audioManager.isMusicActive
                        Log.d(TAG, "초기 오디오 상태: isMusicActive=$isMusicActive")
                        if (isMusicActive) {
                            // 오디오가 재생 중이면 차단 앱 확인
                            val hasBlockedAudio = checkBlockedAppAudio()
                            if (hasBlockedAudio && !isPausedByAudio) {
                                isPausedByAudio = true
                                Log.w(TAG, "초기 상태: 차단 앱 오디오 감지 (isPausedByAudio=true)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "초기 오디오 상태 확인 실패", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "오디오 감시 시작 실패", e)
        }
    }
    
    /**
     * 오디오 감시를 중지하고 콜백을 해제합니다.
     * 🟠 메모리 누수 방지 필수
     */
    private fun stopAudioMonitoring() {
        // API 26+ 체크
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            Log.d(TAG, "Audio Playback Callback unregistered (메모리 누수 방지)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister audio playback callback", e)
        }
    }

    /**
     * AudioPlaybackConfiguration 리스트에서 차단 앱을 실시간으로 검사합니다.
     * ⚠️ 핵심: AudioPlaybackConfiguration 리스트에서 차단 앱 실시간 검사
     * 
     * @param configs 현재 활성 오디오 재생 세션 목록
     * @return 차단 앱에서 오디오가 재생 중이면 true
     */
    private suspend fun checkBlockedAppAudioFromConfigs(configs: List<AudioPlaybackConfiguration>): Boolean {
        return try {
            // configs가 비어있으면 모든 오디오가 꺼진 상태
            if (configs.isEmpty()) {
                Log.d(TAG, "오디오 재생 중인 앱 없음 (configs 비어있음)")
                return false
            }
            
            // ⚠️ 핵심: 현재 오디오를 재생 중인 모든 앱을 차단 목록과 비교
            // 주의: AudioPlaybackConfiguration.getClientUid()는 public API가 아니므로
            // preferenceManager.getLastMiningApp()을 사용하여 마지막 앱 정보로 판단
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val isMusicActive = audioManager.isMusicActive
            if (isMusicActive) {
                val lastApp = preferenceManager.getLastMiningApp()
                if (lastApp != null) {
                    // ⚠️ 핵심: 마지막 앱이 차단 목록에 있는지 확인
                    val isBlocked = withContext(Dispatchers.IO) {
                        database.appBlockDao().getBlockedApp(lastApp) != null
                    }
                    if (isBlocked) {
                        Log.w(TAG, "차단 앱 오디오 재생 감지: $lastApp")
                        return true
                    }
                }
            }
            Log.d(TAG, "오디오 재생 중인 앱 중 차단 앱 없음 (${configs.size}개 세션 확인)")
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check blocked app audio from configs", e)
            false
        }
    }
    
    /**
     * 현재 오디오를 재생하는 앱이 차단 앱 목록에 있는지 확인합니다.
     * 하위 호환성을 위해 유지 (calculateAccumulatedPoints에서 사용)
     * 
     * @return 차단 앱에서 오디오가 재생 중인 것으로 추정되면 true
     */
    private suspend fun checkBlockedAppAudio(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // 1. 현재 오디오가 재생 중인지 확인
            val isMusicActive = audioManager.isMusicActive
            Log.d(TAG, "오디오 감시 체크: isMusicActive=$isMusicActive")
            
            if (!isMusicActive) {
                Log.d(TAG, "오디오 감시: 음악 재생 중 아님")
                return false
            }
            
            // 2. 마지막으로 감지된 앱이 차단 목록에 있었는지 확인
            // PreferenceManager에 저장된 마지막 앱 정보를 활용합니다.
            val lastApp = preferenceManager.getLastMiningApp()
            Log.d(TAG, "오디오 감시: 마지막 앱=$lastApp")
            
            if (lastApp != null) {
                val isBlocked = withContext(Dispatchers.IO) {
                    database.appBlockDao().getBlockedApp(lastApp) != null
                }
                
                Log.d(TAG, "오디오 감시: 앱 차단 여부=$isBlocked (앱=$lastApp)")
                
                if (isBlocked) {
                    Log.w(TAG, "차단 앱($lastApp)에서 오디오 재생 중인 것으로 추정됨")
                    return true
                } else {
                    Log.d(TAG, "오디오 감시: 앱이 차단 목록에 없음 ($lastApp)")
                }
            } else {
                Log.d(TAG, "오디오 감시: 마지막 앱 정보 없음")
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
        // ⚠️ 핵심: 계산된 속성 사용 (isPausedByApp || isPausedByAudio)
        if (isMiningPaused) {
            Log.d(TAG, "차단 앱 사용 중 화면 OFF -> 정산 제외 (isPausedByApp=$isPausedByApp, isPausedByAudio=$isPausedByAudio)")
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
