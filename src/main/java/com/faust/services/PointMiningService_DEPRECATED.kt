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
import kotlin.DeprecationLevel

/**
 * @deprecated TimeCreditBackgroundService로 대체됨 (2026-02 FreePass -> TimeCreditSystem 전환).
 * AndroidManifest.xml에 등록되어 있지 않아 실행되지 않습니다.
 * Zero-Deletion Policy에 따라 코드는 유지되나, 신규 개발 시 참조하지 마세요.
 *
 * 가시성이 internal로 제한되어 외부 모듈에서 접근 불가합니다.
 * companion object 메서드도 private으로 제한하여 실수로 호출하는 것을 컴파일 단계에서 차단합니다.
 *
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
@Deprecated(
    message = "TimeCreditBackgroundService로 대체됨. 이 클래스를 참조하지 마세요.",
    level = DeprecationLevel.WARNING
)
internal class PointMiningServiceLegacy : LifecycleService() {
    private val database: FaustDatabase by lazy {
        (application as FaustApplication).database
    }
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(this)
    }
    private var miningJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var screenEventReceiver: BroadcastReceiver? = null
    
    // 오디오 모니터링 (이벤트 기반)
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    // 상태 관리 변수
    private var isScreenOn = true
    private var isPausedByApp = false  // 앱 실행으로 인한 일시정지 (시각적 차단)
    private var isPausedByAudio = false  // 오디오로 인한 일시정지 (청각적 차단)
    
    // 계산된 속성: isPausedByApp || isPausedByAudio
    private val isMiningPaused: Boolean
        get() = isPausedByApp || isPausedByAudio

    companion object {
        private const val TAG = "PointMiningService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "point_mining_channel"
        
        @Volatile private var instance: PointMiningServiceLegacy? = null
        
        // 상태전이 시스템: AppBlockingService 콜백
        private var blockingServiceCallback: ((Boolean) -> Unit)? = null

        /**
         * [상태전이 시스템] AppBlockingService에 콜백 등록
         */
        private fun setBlockingServiceCallback(service: AppBlockingService) {
            blockingServiceCallback = { isBlocked ->
                service.onAudioBlockStateChanged(isBlocked)
            }
            Log.d(TAG, "BlockingService callback registered")
        }

        private fun startService(context: Context) {
            // 이미 실행 중인 서비스가 있으면 재시작하지 않음
            if (instance != null) {
                Log.d(TAG, "startService() 호출: 서비스가 이미 실행 중 (재시작 스킵)")
                return
            }
            
            Log.d(TAG, "startService() 호출: 새 서비스 시작")
            val intent = Intent(context, PointMiningServiceLegacy::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                    Log.d(TAG, "startForegroundService() 호출 완료")
                } else {
                    context.startService(intent)
                    Log.d(TAG, "startService() 호출 완료")
                }
            } catch (e: Exception) {
                Log.e(TAG, "서비스 시작 실패", e)
            }
        }

        private fun stopService(context: Context) {
            val intent = Intent(context, PointMiningServiceLegacy::class.java)
            context.stopService(intent)
        }
        
        /**
         * 외부에서 포인트 적립을 일시 중단합니다.
         * (앱 실행으로 인한 시각적 차단)
         */
        private fun pauseMining() {
            instance?.let {
                it.isPausedByApp = true
                Log.d(TAG, "[채굴 중단] 앱 차단으로 인한 일시정지")
                Log.d(TAG, "[채굴 상태] isPausedByApp=${it.isPausedByApp}, isPausedByAudio=${it.isPausedByAudio}, isMiningPaused=${it.isMiningPaused}")
            }
        }
        
        /**
         * 외부에서 포인트 적립을 재개합니다.
         * (앱 실행 차단 해제)
         */
        private fun resumeMining() {
            instance?.let {
                it.isPausedByApp = false
                Log.d(TAG, "[채굴 재개] 앱 차단 해제로 인한 재개")
                Log.d(TAG, "[채굴 상태] isPausedByApp=${it.isPausedByApp}, isPausedByAudio=${it.isPausedByAudio}, isMiningPaused=${it.isMiningPaused}")
            }
        }
        
        /**
         * 현재 포인트 적립이 일시 중단되었는지 확인합니다.
         */
        private fun isMiningPaused(): Boolean {
            return instance?.isMiningPaused ?: false
        }

        /**
         * 현재 차단 앱 오디오로 인해 일시정지 중인지 확인합니다.
         * 화면 OFF 시 상태를 기록하기 위해 사용됩니다.
         */
        private fun isPausedByAudio(): Boolean {
            return instance?.isPausedByAudio ?: false
        }

        /**
         * 사용자가 '강행'을 선택했을 때 단 한 번 벌금을 부과합니다.
         * @param context Context (ApplicationContext 권장)
         * @param penaltyAmount 벌금 액수 (예: 10)
         */
        private fun applyOneTimePenalty(context: Context, penaltyAmount: Int) {
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
        Log.d(TAG, "onCreate() 호출: 서비스 인스턴스 생성")
        instance = this
        createNotificationChannel()
        // Foreground Service 시작 (앱이 종료되어도 죽지 않음)
        startForeground(NOTIFICATION_ID, createNotification())
        preferenceManager.setServiceRunning(true)
        Log.d(TAG, "Foreground Service 시작 완료: Notification 표시")
        
        // 화면 이벤트 리시버 등록
        registerScreenEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        Log.d(TAG, "Mining Service Started (startId=$startId, flags=$flags)")
        
        // 실제 화면 상태 확인 및 초기화
        checkAndUpdateScreenState()
        
        // 이미 실행 중인 job이 있으면 재시작하지 않음 (중복 방지)
        if (miningJob?.isActive == true) {
            Log.d(TAG, "Mining Job이 이미 실행 중: 재시작 스킵 (기존 job 유지)")
        } else {
            Log.d(TAG, "Mining Job 시작: 새 코루틴 생성")
            startMiningJob()
        }
        
        // 오디오 모니터링 시작 (화면 상태와 무관하게 지속 실행)
        startAudioMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() 호출: 서비스 종료 시작")
        instance = null
        miningJob?.cancel()
        Log.d(TAG, "Mining Job 취소 완료")
        serviceScope.cancel()
        Log.d(TAG, "ServiceScope 취소 완료")
        stopAudioMonitoring()  // 오디오 콜백 해제
        unregisterScreenEventReceiver()
        preferenceManager.setServiceRunning(false)
        Log.d(TAG, "Mining Service Stopped: 모든 리소스 정리 완료")
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
        Log.d(TAG, "startMiningJob() 호출: 기존 job 취소 후 새 job 시작")
        miningJob = serviceScope.launch {
            Log.d(TAG, "Mining 코루틴 시작: isActive=$isActive, isScreenOn=$isScreenOn, isMiningPaused=$isMiningPaused")
            var iterationCount = 0
            while (isActive) {
                try {
                    iterationCount++
                    Log.d(TAG, "Mining 루프 반복 시작: iteration=$iterationCount, 1분 대기 시작...")
                    delay(60_000L) // 1분 대기
                    Log.d(TAG, "Mining 루프: 1분 경과, 상태 확인 시작 (isScreenOn=$isScreenOn, isMiningPaused=$isMiningPaused)")
                    if (isScreenOn && !isMiningPaused) {
                        // [C-2] 크레딧 세션 중: 포인트 적립 스킵 (차감은 TimeCreditBackgroundService에서 수행)
                        if (preferenceManager.isCreditSessionActive()) {
                            Log.d(TAG, "Credit Session 활성: 포인트 적립 스킵 (크레딧 차감은 TimeCreditBackgroundService에서 수행)")
                        } else {
                            // 일반 마이닝
                            Log.d(TAG, "Mining 루프: 조건 충족, 포인트 적립 시작")
                            addMiningPoints(1)
                            Log.d(TAG, "포인트 적립: 1 WP (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isMiningPaused)")
                        }
                    } else {
                        Log.d(TAG, "포인트 적립 스킵 (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isMiningPaused)")
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Mining 코루틴 취소됨: iteration=$iterationCount")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in mining loop: iteration=$iterationCount", e)
                }
            }
            Log.d(TAG, "Mining 코루틴 종료: isActive=$isActive, iteration=$iterationCount")
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

            // 마지막 포인트 적립 시간 기록 (TimeCredit 정산용)
            preferenceManager.setLastMiningTime(System.currentTimeMillis())
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
                        // 주의: 오디오 모니터링은 화면 상태와 무관하게 계속 실행됨
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
     * 오디오 모니터링 시작 (이벤트 기반)
     * AudioPlaybackCallback을 사용하여 오디오 상태 변경 시 즉시 감지합니다.
     * 화면 상태(ON/OFF)와 무관하게 지속적으로 작동합니다.
     */
    private fun startAudioMonitoring() {
        // API 26+ 체크
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "AudioPlaybackCallback requires API 26+, audio monitoring disabled")
            return
        }

        try {
            // 기존 콜백이 있으면 해제
            stopAudioMonitoring()

            // 이벤트 기반 오디오 콜백 등록
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    super.onPlaybackConfigChanged(configs)
                    Log.d(TAG, "오디오 콜백 호출: ${configs.size}개 세션 감지")
                    
                    // ANR 방지: 코루틴으로 전환
                    serviceScope.launch {
                        checkBlockedAppAudioFromConfigs(configs)
                    }
                }
            }
            audioPlaybackCallback = callback

            audioManager.registerAudioPlaybackCallback(callback, null)
            Log.d(TAG, "Audio Monitoring Started (Event-based)")

            // 초기 오디오 상태 확인
            serviceScope.launch {
                checkInitialAudioState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio monitoring", e)
        }
    }

    /**
     * 오디오 모니터링 중지
     */
    private fun stopAudioMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        try {
            audioPlaybackCallback?.let {
                audioManager.unregisterAudioPlaybackCallback(it)
                audioPlaybackCallback = null
                Log.d(TAG, "Audio Monitoring Stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio monitoring", e)
        }
    }

    /**
     * 초기 오디오 상태 확인
     * 콜백 등록 직후 현재 오디오 상태를 확인합니다.
     */
    private suspend fun checkInitialAudioState() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: activePlaybackConfigurations로 활성 세션 확인
                val activeConfigs = audioManager.activePlaybackConfigurations
                if (activeConfigs.isNotEmpty()) {
                    Log.d(TAG, "초기 오디오 상태 확인: ${activeConfigs.size}개 활성 세션")
                    checkBlockedAppAudioFromConfigs(activeConfigs)
                }
            } else {
                // API 26-28: isMusicActive로 초기 상태 확인
                if (audioManager.isMusicActive) {
                    Log.d(TAG, "초기 오디오 상태 확인: 음악 재생 중")
                    checkBlockedAppAudio()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check initial audio state", e)
        }
    }

    /**
     * 오디오 모니터링 - 이벤트 기반
     * AudioPlaybackCallback에서 호출됩니다.
     * 
     * 오디오 상태가 변경되었을 때 한 번만 검사하고, 검사 결과를 저장하여 포인트 채굴 여부를 결정합니다.
     * 주기적 검사가 아닌 이벤트 기반으로 작동하여 배터리 소모를 최소화합니다.
     * 
     * @param configs 현재 활성 오디오 재생 세션 목록
     */
    private suspend fun checkBlockedAppAudioFromConfigs(configs: List<AudioPlaybackConfiguration>) {
        try {
            Log.d(TAG, "[오디오 검사] 시작: 세션 수=${configs.size}, isMusicActive=${audioManager.isMusicActive}, 현재 상태: isPausedByAudio=$isPausedByAudio")
            
            // 오버레이가 표시 중이면 PersonaEngine의 오디오 재생일 가능성이 높으므로 검사 건너뛰기
            if (AppBlockingService.isPersonaAudioPossiblyPlaying()) {
                Log.d(TAG, "[오디오 검사] 오버레이 표시 중: PersonaEngine 오디오 재생으로 추정되어 검사 건너뜀")
                return
            }
            
            // 활성 세션이 없으면 오디오 종료로 판단
            // 주의: PLAYER_STATE_STARTED는 @SystemApi이므로 공개 API가 아닙니다.
            // 대신 configs 리스트가 비어있지 않고 AudioManager.isMusicActive를 사용합니다.
            val hasActiveAudio = configs.isNotEmpty() && audioManager.isMusicActive
            Log.d(TAG, "[오디오 검사] 활성 오디오 확인: hasActiveAudio=$hasActiveAudio (configs.size=${configs.size}, isMusicActive=${audioManager.isMusicActive})")

            if (!hasActiveAudio) {
                // 오디오 종료 감지
                if (isPausedByAudio) {
                    Log.d(TAG, "[오디오 검사] 오디오 종료 감지: 차단 앱 오디오 재생 중단")
                    isPausedByAudio = false
                    Log.w(TAG, "[채굴 재개] 차단 앱 오디오 종료로 인한 재개")
                    Log.d(TAG, "[채굴 상태] isPausedByApp=$isPausedByApp, isPausedByAudio=$isPausedByAudio, isMiningPaused=$isMiningPaused")
                    // 화면 OFF 시 차단 앱 오디오 재생 기록 리셋
                    preferenceManager.setAudioBlockedOnScreenOff(false)
                    Log.d(TAG, "화면 OFF 시 차단 앱 오디오 재생 기록 리셋")
                    // 상태전이 시스템: 콜백 호출
                    blockingServiceCallback?.invoke(false)
                } else {
                    Log.d(TAG, "[오디오 검사] 오디오 종료: 이미 재생 중이 아님 (isPausedByAudio=false)")
                }
                return
            }

            // 오디오 재생 중: 차단 앱 확인
            Log.d(TAG, "[오디오 검사] 오디오 재생 중: 차단 앱 확인 시작")
            val hasBlockedAppAudio = checkBlockedAppAudio()
            Log.d(TAG, "[오디오 검사] 차단 앱 확인 결과: hasBlockedAppAudio=$hasBlockedAppAudio, 현재 상태: isPausedByAudio=$isPausedByAudio")

            if (hasBlockedAppAudio && !isPausedByAudio) {
                // 차단 앱에서 오디오 재생 중이면 포인트 채굴 일시정지
                Log.d(TAG, "[오디오 검사] 차단 앱 오디오 감지: 일시정지 상태로 전환")
                isPausedByAudio = true
                Log.w(TAG, "[채굴 중단] 차단 앱 오디오 감지로 인한 일시정지")
                Log.d(TAG, "[채굴 상태] isPausedByApp=$isPausedByApp, isPausedByAudio=$isPausedByAudio, isMiningPaused=$isMiningPaused")
                // 상태전이 시스템: 콜백 호출
                blockingServiceCallback?.invoke(true)
            } else if (!hasBlockedAppAudio && isPausedByAudio) {
                // 오디오 종료 감지
                Log.d(TAG, "[오디오 검사] 차단 앱 오디오 종료: 재개 상태로 전환")
                isPausedByAudio = false
                Log.w(TAG, "[채굴 재개] 차단 앱 오디오 종료로 인한 재개")
                Log.d(TAG, "[채굴 상태] isPausedByApp=$isPausedByApp, isPausedByAudio=$isPausedByAudio, isMiningPaused=$isMiningPaused")
                // 화면 OFF 시 차단 앱 오디오 재생 기록 리셋
                preferenceManager.setAudioBlockedOnScreenOff(false)
                Log.d(TAG, "화면 OFF 시 차단 앱 오디오 재생 기록 리셋")
                // 상태전이 시스템: 콜백 호출
                blockingServiceCallback?.invoke(false)
            } else {
                Log.d(TAG, "[오디오 검사] 상태 변경 없음: hasBlockedAppAudio=$hasBlockedAppAudio, isPausedByAudio=$isPausedByAudio")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[오디오 검사] 오류 발생", e)
        }
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
            Log.d(TAG, "[차단 앱 오디오 확인] 시작")
            
            // 1. 현재 오디오가 재생 중인지 확인
            val isMusicActive = audioManager.isMusicActive
            Log.d(TAG, "[차단 앱 오디오 확인] 1단계: 오디오 재생 상태 확인 - isMusicActive=$isMusicActive")
            if (!isMusicActive) {
                Log.d(TAG, "[차단 앱 오디오 확인] 오디오 재생 중이 아님: false 반환")
                return false
            }
            
            // 2. 마지막으로 감지된 앱이 차단 목록에 있었는지 확인
            // PreferenceManager에 저장된 마지막 앱 정보를 활용합니다.
            val lastApp = preferenceManager.getLastMiningApp()
            Log.d(TAG, "[차단 앱 오디오 확인] 2단계: 마지막 앱 확인 - lastApp=$lastApp")
            
            if (lastApp != null) {
                val isBlocked = withContext(Dispatchers.IO) {
                    val blockedApp = database.appBlockDao().getBlockedApp(lastApp)
                    blockedApp != null
                }
                Log.d(TAG, "[차단 앱 오디오 확인] 3단계: 차단 목록 확인 - lastApp=$lastApp, isBlocked=$isBlocked")
                
                if (isBlocked) {
                    Log.d(TAG, "[차단 앱 오디오 확인] 결과: 차단 앱($lastApp)에서 오디오 재생 중인 것으로 추정됨 - true 반환")
                    Log.d(TAG, "차단 앱($lastApp)에서 오디오 재생 중인 것으로 추정됨")
                    return true
                } else {
                    Log.d(TAG, "[차단 앱 오디오 확인] 결과: 마지막 앱($lastApp)은 차단 목록에 없음 - false 반환")
                }
            } else {
                Log.d(TAG, "[차단 앱 오디오 확인] 결과: 마지막 앱 정보 없음 - false 반환")
            }
            
            Log.d(TAG, "[차단 앱 오디오 확인] 최종 결과: false 반환")
            false
        } catch (e: Exception) {
            Log.e(TAG, "[차단 앱 오디오 확인] 오류 발생", e)
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

            // [C-1] 절제 시간 누적 (TimeCredit 정산용, 초 단위). Credit Session 활성 시 이중 정산 방지.
            if (!preferenceManager.isCreditSessionActive()) {
                val offDurationSeconds = (offDurationMinutes * 60L).coerceAtLeast(0L)
                val accumulated = preferenceManager.getAccumulatedAbstentionSeconds()
                preferenceManager.setAccumulatedAbstentionSeconds(accumulated + offDurationSeconds)
                Log.d(TAG, "절제 시간 누적: +${offDurationMinutes}분 (총 ${(accumulated + offDurationSeconds) / 60}분)")
            } else {
                Log.d(TAG, "Credit Session 활성: 절제 시간 누적 스킵 (이중 정산 방지)")
            }
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
                getString(R.string.notification_channel_time_credit),
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
            .setContentTitle(getString(R.string.notification_time_credit_title))
            .setContentText("열심히 포인트를 채굴하고 있어요 ⛏️")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
