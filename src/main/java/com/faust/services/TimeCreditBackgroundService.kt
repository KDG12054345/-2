package com.faust.services

import android.app.AlarmManager
import android.app.Notification
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.faust.FaustApplication
import com.faust.R
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.domain.PenaltyService
import com.faust.domain.TimeCreditService
import com.faust.presentation.view.MainActivity
import com.faust.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 3: 시간 소스 추상화 (Testability 향상)
 * TimeSource 인터페이스로 테스트에서 시간 주입 가능하도록 함.
 */
interface TimeSource {
    fun elapsedRealtime(): Long
}

class SystemTimeSource : TimeSource {
    override fun elapsedRealtime() = SystemClock.elapsedRealtime()
}

class FakeTimeSource(var currentTime: Long = 0L) : TimeSource {
    override fun elapsedRealtime() = currentTime
}

/**
 * [시스템 진입점: 백그라운드 유지 진입점]
 *
 * 역할: 타임 크레딧 전용 백그라운드 서비스. 화면 ON/OFF, 1분 주기 루프, 오디오 모니터링으로
 * 누적 절제 분을 쌓고 Credit Session 시 잔액을 차감합니다. 포인트(WP) 및 PointTransaction 미사용.
 *
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class TimeCreditBackgroundService : LifecycleService() {

    private val database: FaustDatabase by lazy {
        (application as FaustApplication).database
    }
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(this)
    }
    private val timeCreditService: TimeCreditService by lazy {
        TimeCreditService(this)
    }

    private var tickJob: Job? = null
    private var sessionMonitorJob: Job? = null
    /** 화면 OFF 시 차단 앱 오디오 재생 중일 때만 동작하는 1초 주기 차감. 오디오 콜백에서도 동일 잡 사용. */
    @Volatile
    private var screenOffDeductionJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var screenEventReceiver: BroadcastReceiver? = null

    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var isScreenOn = true
    private var isPausedByApp = false
    private var isPausedByAudio = false

    /** Start of current minute window (elapsedRealtime). Used for final deduction on Screen OFF. */
    @Volatile
    private var lastTickElapsedRealtime: Long = 0L

    /** Exhaustion timer: blocks at exact zero-credit time instead of waiting for next tick. */
    private val exhaustionHandler = Handler(Looper.getMainLooper())
    private val exhaustionRunnable = Runnable { runExhaustionTimer() }

    /** Adaptive Monitoring: serializes syncState() to guarantee atomicity. */
    private val syncStateLock = Any()

    /** 마지막으로 아는 포그라운드 패키지(메모리만, 미 persist). 세션 앱과 같을 때만 사용분 차감. */
    @Volatile
    private var lastKnownForegroundPackage: String? = null

    /** Phase 3: 시간 소스 추상화 - 테스트에서 주입 가능하도록 인스턴스 필드로 선언 */
    private var timeSource: TimeSource = SystemTimeSource()

    /** Phase 3: 운영 지표 수집 - 멱등성 스킵 횟수 */
    private var idempotencySkipCount: AtomicLong = AtomicLong(0L)

    /** Phase 3: 운영 지표 수집 - 세이프 가드 발동 횟수 */
    private var physicalLimitGuardTriggerCount: AtomicLong = AtomicLong(0L)

    /** 크레딧 차감·소진 일원화: "차감만 / 소진 알림" 판단 결과. ExhaustAndNotify일 때만 notifyCreditExhausted() 호출. */
    private sealed class CreditAction {
        object NoOp : CreditAction()
        object ExhaustAndNotify : CreditAction()
    }

    /**
     * Phase 5: 상태 머신 정의
     */
    private enum class CreditSessionState {
        ACTIVE,      // 세션 앱이 포그라운드
        DORMANT,     // 세션 앱이 백그라운드
        INACTIVE     // 세션 비활성
    }

    /**
     * Phase 5: 현재 상태 결정
     */
    private fun determineCurrentState(): CreditSessionState {
        if (!preferenceManager.isCreditSessionActive()) return CreditSessionState.INACTIVE
        val sessionPkg = preferenceManager.getCreditSessionPackage() ?: return CreditSessionState.INACTIVE
        return if (lastKnownForegroundPackage == sessionPkg) {
            CreditSessionState.ACTIVE
        } else {
            CreditSessionState.DORMANT
        }
    }

    /**
     * "차감/소진이 필요한가?" 판단만 수행. 호출처: 세션 모니터, 틱, 화면 OFF 1초 잡, syncState→handleZoneTransition, 정밀 알람, 소진 타이머.
     * apply는 각 호출처에서 수행; ExhaustAndNotify일 때만 notifyCreditExhausted() 호출 (락 해제 후).
     * 세션 모니터는 accumulatedUsageSec+currentSegmentUsageSec(실제 정산된 합+현재 구간) 사용 → 휴면 오판 시에도 유령 차감 방지.
     */
    private fun evaluateCreditAction(
        balanceAfter: Long? = null,
        durationSec: Long? = null,
        creditAtStart: Long? = null,
        accumulatedUsageSec: Long? = null,
        currentSegmentUsageSec: Long? = null
    ): CreditAction {
        if (accumulatedUsageSec != null && currentSegmentUsageSec != null && creditAtStart != null && creditAtStart > 0L &&
            (accumulatedUsageSec + currentSegmentUsageSec) >= creditAtStart
        ) {
            return CreditAction.ExhaustAndNotify
        }
        if (durationSec != null && creditAtStart != null && creditAtStart > 0L && durationSec >= creditAtStart) {
            return CreditAction.ExhaustAndNotify
        }
        if (balanceAfter != null && balanceAfter <= 0L) {
            return CreditAction.ExhaustAndNotify
        }
        return CreditAction.NoOp
    }

    /** notifyCreditExhausted() 중복 호출 방지: 세션 모니터와 정밀 알람이 동시에 부를 수 있음. */
    private val exhaustionLock = Any()
    @Volatile
    private var creditExhaustionInProgress = false

    private val isPaused: Boolean
        get() = isPausedByApp || isPausedByAudio

    companion object {
        private const val TAG = "TimeCreditBgService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "time_credit_channel"

        private const val TICK_INTERVAL_SECONDS = 60L
        private const val SESSION_MONITOR_INTERVAL_MS = 30_000L
        private const val MAX_OFF_DURATION_SECONDS_CAP = 24L * 60 * 60
        /** Anomaly guardrail: max plausible session duration (24h) to prevent Abnormal Batch Deductions. */
        private const val MAX_SESSION_DURATION_SECONDS = 24L * 60 * 60
        /** Adaptive Monitoring: C > this = safe zone; 0 < C <= this = danger zone. */
        private const val THRESHOLD_DANGER_ZONE_SECONDS = 60L
        private const val REQUEST_CODE_PRECISION_TRANSITION = 5003

        @Volatile
        private var instance: TimeCreditBackgroundService? = null

        private var blockingServiceCallback: ((Boolean) -> Unit)? = null
        private var creditExhaustedCallback: ((String) -> Unit)? = null
        /** Screen ON 시 정산(calculateAccumulatedAbstention + settleCredits) 완료 후 호출. 잔액 갱신 후 포그라운드 재진입 분기용. */
        private var screenOnSettlementDoneCallback: (() -> Unit)? = null
        /** 화면 OFF 수신 시 (1) 즉시 호출 — 도주 패널티·오버레이 정리용. 인자 없음; ABS가 콜백 내부에서 자기 상태만 읽음. */
        private var screenOffEventImmediateCallback: (() -> Unit)? = null
        /** 화면 OFF 200ms 후 오디오 확정 시 호출. TCB가 preference 저장 후 알림용. */
        private var screenOffEventAudioResolvedCallback: ((Boolean) -> Unit)? = null
        /** 화면 OFF 시 오디오 검사용 candidate 패키지. ABS 제공. */
        private var screenOffAudioCandidateProvider: (() -> String?)? = null

        fun setScreenOffEventCallback(immediate: () -> Unit, audioResolved: (Boolean) -> Unit) {
            screenOffEventImmediateCallback = immediate
            screenOffEventAudioResolvedCallback = audioResolved
            Log.d(TAG, "ScreenOffEvent callbacks registered")
        }

        fun setScreenOffAudioCandidateProvider(provider: () -> String?) {
            screenOffAudioCandidateProvider = provider
            Log.d(TAG, "ScreenOffAudioCandidate provider registered")
        }

        fun setBlockingServiceCallback(service: AppBlockingService) {
            blockingServiceCallback = { isBlocked ->
                service.onAudioBlockStateChanged(isBlocked)
            }
            Log.d(TAG, "BlockingService callback registered")
        }

        fun setCreditExhaustedCallback(callback: (String) -> Unit) {
            creditExhaustedCallback = callback
            Log.d(TAG, "CreditExhausted callback registered")
        }

        fun setScreenOnSettlementDoneCallback(callback: () -> Unit) {
            screenOnSettlementDoneCallback = callback
            Log.d(TAG, "ScreenOnSettlementDone callback registered")
        }

        fun isServiceRunning(): Boolean = instance != null

        internal fun fireCreditExhaustedCallback(packageName: String) {
            creditExhaustedCallback?.invoke(packageName)
        }

        fun startService(context: Context) {
            if (instance != null) {
                Log.d(TAG, "startService() 호출: 서비스가 이미 실행 중 (재시작 스킵)")
                return
            }
            Log.d(TAG, "startService() 호출: 새 서비스 시작")
            val intent = Intent(context, TimeCreditBackgroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "서비스 시작 실패", e)
            }
        }

        /** 1분 전 알람은 골든 타임 제거로 미사용. 수신만 허용해 호출부 호환 유지. */
        fun notifyGoldenTimeAlarm(context: Context) {
            // No-op: golden time removed; precision transition at balance seconds only.
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TimeCreditBackgroundService::class.java)
            context.stopService(intent)
        }

        fun pauseMining() {
            instance?.let {
                it.isPausedByApp = true
                Log.i(TAG, "${AppLog.CREDIT} app blocked → mining paused")
            }
        }

        fun resumeMining() {
            instance?.let {
                it.isPausedByApp = false
                Log.i(TAG, "${AppLog.CREDIT} app unblocked → mining resumed")
            }
        }

        fun isMiningPaused(): Boolean = instance?.isPaused ?: false
        fun isPausedByAudio(): Boolean = instance?.isPausedByAudio ?: false

        /**
         * 화면 OFF 시점에 동기적으로 "차단 앱 오디오 재생 중인지" 계산합니다.
         * [candidatePackage]가 있으면 해당 패키지를 우선 사용(화면 OFF 시점 포그라운드 고정), 없으면 lastMiningApp 사용.
         * Credit 세션에서 넘긴 후보는 이미 차단 앱이므로 DB isBlocked 검사 생략.
         * 차단 목록에 있고, (스냅샷 재생 중 OR 콜백 isPausedByAudio)이면 true.
         */
        fun computeAudioBlockedOnScreenOff(candidatePackage: String? = null): Boolean {
            val inst = instance ?: return false
            if (AppBlockingService.isPersonaAudioPossiblyPlaying()) return false
            val lastApp = candidatePackage ?: inst.preferenceManager.getLastMiningApp() ?: return false
            val isBlocked = if (
                candidatePackage != null &&
                inst.preferenceManager.isCreditSessionActive() &&
                inst.preferenceManager.getCreditSessionPackage() == candidatePackage
            ) {
                true
            } else {
                runBlocking(Dispatchers.IO) {
                    inst.database.appBlockDao().getBlockedApp(lastApp) != null
                }
            }
            if (!isBlocked) return false
            val audioBySnapshot = computeCurrentAudioStatus(lastApp)
            val audioByCallback = isPausedByAudio()
            return audioBySnapshot || audioByCallback
        }

        /**
         * 지정한 패키지가 현재 오디오 재생 중(STATE_PLAYING)인지 동기적으로 조회합니다.
         * MediaSessionManager로 해당 패키지의 활성 세션을 확인하고, 없으면
         * audioManager.isMusicActive + 마지막 앱이 packageName인지로 폴백합니다.
         * Persona 오디오 재생 중이면 항상 false를 반환합니다.
         *
         * @param packageName 확인할 앱 패키지명
         * @return 해당 앱이 현재 재생 중이면 true
         */
        fun computeCurrentAudioStatus(packageName: String): Boolean =
            instance?.computeCurrentAudioStatusInternal(packageName) ?: false

        /**
         * Credit Session 종료 직전에 호출. 마지막 Tick 이후 경과 시간을 잔액에서 차감합니다.
         * AppBlockingService에서 endCreditSession() 호출 전에 반드시 호출해야 합니다.
         */
        fun performFinalDeduction() {
            instance?.performFinalDeductionOnSessionEnd()
        }

        /**
         * 잔액이 있을 때 차단 앱 이탈 시 즉시 종료하지 않고 휴면(Dormant) 진입을 강제합니다.
         * targetPackageName: 현재 포그라운드(비차단 앱). 세션은 유지하고 1초 차감만 중단합니다.
         */
        fun requestDormant(targetPackageName: String) {
            instance?.requestDormantInternal(targetPackageName)
        }

        /** 차단 앱 진입/재진입 시 호출. 휴면 해제 및 사용분 차감 허용. */
        fun setLastKnownForegroundPackage(packageName: String?) {
            instance?.setLastKnownForegroundPackageInternal(packageName)
        }

        /** Adaptive Monitoring: Credit Session 시작 시 구간 판정 및 알람/틱 설정. */
        fun onCreditSessionStarted() {
            instance?.let {
                it.clearExhaustionGuard()
                it.syncState()
            }
        }

        /**
         * 화면 OFF가 아닌 경로에서도 1초 차감 잡을 시작.
         * Credit Session 활성, 세션 앱 패키지 존재, 해당 앱 오디오 ON일 때만 동작.
         */
        fun startBackgroundAudioDeductionJobIfNeeded() {
            instance?.startScreenOffDeductionJobIfNeeded()
        }

        /** 1초 차감 잡(화면 OFF / 오디오 콜백 공용) 취소. */
        fun stopBackgroundAudioDeductionJob() {
            instance?.stopScreenOffDeductionJob()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() 호출")
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        preferenceManager.setServiceRunning(true)
        // Phase 1: 상태 영속화 - 서비스 시작 시 lastKnownForegroundPackage 복구
        lastKnownForegroundPackage = preferenceManager.getLastKnownForegroundPackage()
        registerScreenEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            TimeCreditService.ACTION_PRECISION_TRANSITION -> {
                runBlocking(Dispatchers.Default) { syncState() }
                return START_STICKY
            }
        }
        checkAndUpdateScreenState()
        tryRecoverSessionIfKilled()
        if (tickJob?.isActive == true) {
            Log.d(TAG, "Tick Job이 이미 실행 중: 재시작 스킵")
        } else {
            startTickJob()
        }
        startAudioMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            preferenceManager.persistTimeCreditValues(synchronous = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist time credit on service destroy", e)
        }
        super.onDestroy()
        instance = null
        cancelExhaustionTimer()
        stopScreenOffDeductionJob()
        sessionMonitorJob?.cancel()
        sessionMonitorJob = null
        tickJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        stopAudioMonitoring()
        unregisterScreenEventReceiver()
        preferenceManager.setServiceRunning(false)
        Log.d(TAG, "TimeCreditBackgroundService Stopped")
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun checkAndUpdateScreenState() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            }
            Log.d(TAG, "화면 상태 확인: ${if (isScreenOn) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e(TAG, "화면 상태 확인 실패", e)
        }
    }

    /**
     * Phase 6: 복구 시나리오 검증 강화 + 리부팅 대응
     * 벽시계 제거: (lastAlive - startElapsed) 사용 안 함
     * 누적 합산: creditAtStart - balanceBefore
     * 물리적 한계 검증 및 리부팅 감지 추가
     */
    private fun tryRecoverSessionIfKilled() {
        if (!preferenceManager.isCreditSessionActive()) return

        val lastAlive = preferenceManager.getLastKnownAliveSessionTime()
        val startElapsed = preferenceManager.getCreditSessionStartElapsedRealtime()
        val lastSync = preferenceManager.getTimeCreditLastSyncTime()
        val nowElapsed = timeSource.elapsedRealtime()

        // 리부팅 감지: 저장된 시간이 현재보다 크면 재부팅 발생
        if (lastAlive > nowElapsed || startElapsed > nowElapsed || lastSync > nowElapsed) {
            Log.w(TAG, "${AppLog.CONSISTENCY} Reboot detected in recovery (lastAlive=$lastAlive, start=$startElapsed, lastSync=$lastSync > now=$nowElapsed), resetting session")
            // 재부팅 후에는 세션 상태 초기화
            timeCreditService.endCreditSession()
            return
        }

        val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
        val balanceBefore = preferenceManager.getTimeCreditBalanceSeconds()
        val accumulatedUsage = (creditAtStart - balanceBefore).coerceAtLeast(0L)

        // 검증: 누적 사용량이 비정상적으로 크면 경고
        if (accumulatedUsage > MAX_SESSION_DURATION_SECONDS) {
            Log.w(TAG, "${AppLog.CONSISTENCY} Recovery: Abnormal accumulatedUsage=${accumulatedUsage}s, capping to ${MAX_SESSION_DURATION_SECONDS}s")
            timeCreditService.endCreditSession()
            return
        }

        // 검증: balanceBefore가 creditAtStart보다 크면 비정상
        if (balanceBefore > creditAtStart) {
            Log.w(TAG, "${AppLog.CONSISTENCY} Recovery: Abnormal balanceBefore=${balanceBefore}s > creditAtStart=${creditAtStart}s, resetting")
            preferenceManager.setTimeCreditBalanceSeconds(creditAtStart)
        }

        // 복구 정산 로직 (누적 합산 방식)
        // 리부팅이 감지되지 않았으므로 정상 복구 수행

        // 정상 복구 시: lastSyncTime을 현재 시간으로 맞추고, 필요 시 한 번만 정산
        // 누적 합산 방식이므로 이미 깎인 시간(accumulatedUsage)은 그대로 유지
        // 마지막 구간(lastSync ~ now)만 정산하면 됨
        val snapshotNow = timeSource.elapsedRealtime()
        val lastSyncRecovery = preferenceManager.getTimeCreditLastSyncTime()

        // lastSync가 0이거나 현재보다 작으면, 마지막 구간 정산 수행
        val finalSegmentUsage = if (lastSyncRecovery > 0L && lastSyncRecovery < snapshotNow) {
            ((snapshotNow - lastSyncRecovery) / 1000L).coerceAtLeast(0L)
        } else 0L

        // 물리적 한계 검증
        val physicalTimeLimit = if (startElapsed > 0L) {
            (snapshotNow - startElapsed) / 1000L
        } else Long.MAX_VALUE
        
        // 세이프 가드 발동 감지
        if (finalSegmentUsage > physicalTimeLimit) {
            physicalLimitGuardTriggerCount.incrementAndGet()
            Log.w(TAG, "${AppLog.CONSISTENCY} Physical limit guard triggered in recovery: calculated=$finalSegmentUsage, limit=$physicalTimeLimit [triggerCount=${physicalLimitGuardTriggerCount.get()}]")
        }
        
        val usageToDeduct = minOf(finalSegmentUsage, physicalTimeLimit, balanceBefore)
        val balanceAfter = (balanceBefore - usageToDeduct).coerceAtLeast(0L)

        // 원자적 저장 (복구 시에는 synchronous=true로 정합성 보장)
        preferenceManager.persistBalanceAndLastSyncTime(balanceAfter, snapshotNow, synchronous = true)

        Log.d(TAG, "${AppLog.CONSISTENCY} Recovery: accumulated=$accumulatedUsage, finalSegment=$finalSegmentUsage, deducted=$usageToDeduct, balance=$balanceBefore→$balanceAfter")
        timeCreditService.endCreditSession()
        preferenceManager.setLastKnownAliveSessionTime(0L)
        val balanceNow = preferenceManager.getTimeCreditBalanceSeconds()
        Log.w(TAG, "${AppLog.CREDIT} recovery after abnormal process exit → session restored (balance=${balanceNow}s)")
    }

    private fun startSessionMonitor() {
        sessionMonitorJob?.cancel()
        sessionMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(SESSION_MONITOR_INTERVAL_MS)
                    if (!preferenceManager.isCreditSessionActive()) continue
                    val sessionPkg = preferenceManager.getCreditSessionPackage() ?: continue
                    // 휴면: AppBlockingService가 갱신한 포그라운드가 세션 앱이 아니면 소진 체크 스킵 (UsageStats 폴백 오판 방지)
                    val isDormant = when {
                        lastKnownForegroundPackage == null -> !isSessionAppInForeground(sessionPkg)
                        else -> lastKnownForegroundPackage != sessionPkg
                    }
                    if (isDormant) continue
                    val nowElapsed = SystemClock.elapsedRealtime()
                    preferenceManager.setLastKnownAliveSessionTime(nowElapsed)
                    val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
                    if (creditAtStart <= 0L) continue
                    val balance = preferenceManager.getTimeCreditBalanceSeconds()
                    val accumulatedUsage = (creditAtStart - balance).coerceAtLeast(0L)
                    val currentSegmentUsage = calculateUsageSinceLastSync(timeSource.elapsedRealtime())
                    if (evaluateCreditAction(
                            creditAtStart = creditAtStart,
                            accumulatedUsageSec = accumulatedUsage,
                            currentSegmentUsageSec = currentSegmentUsage
                        ) is CreditAction.ExhaustAndNotify
                    ) {
                        Log.d(TAG, "${AppLog.CREDIT} session monitor: accumulated ${accumulatedUsage}s + segment ${currentSegmentUsage}s >= credit ${creditAtStart}s → trigger exhaustion")
                        notifyCreditExhausted()
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in session monitor", e)
                }
            }
        }
        Log.d(TAG, "Session monitor started (interval ${SESSION_MONITOR_INTERVAL_MS / 1000}s)")
    }

    private fun stopSessionMonitor() {
        sessionMonitorJob?.cancel()
        sessionMonitorJob = null
    }

    private fun startTickJob() {
        tickJob?.cancel()
        lastTickElapsedRealtime = SystemClock.elapsedRealtime()
        if (preferenceManager.isCreditSessionActive()) {
            startSessionMonitor()
        }
        tickJob = serviceScope.launch {
            var localLastTick = lastTickElapsedRealtime
            while (isActive) {
                try {
                    delay(60_000L)
                    preferenceManager.persistTimeCreditValues()

                    if (preferenceManager.isCreditSessionActive()) {
                        if (sessionMonitorJob?.isActive != true) startSessionMonitor()
                        if (!isScreenOn || isPaused) {
                            localLastTick = SystemClock.elapsedRealtime()
                            lastTickElapsedRealtime = localLastTick
                            Log.d(TAG, "Tick 스킵 (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isPaused)")
                        }
                        continue
                    }
                    stopSessionMonitor()
                    if (!isScreenOn || isPaused) {
                        localLastTick = SystemClock.elapsedRealtime()
                        lastTickElapsedRealtime = localLastTick
                        Log.d(TAG, "Tick 스킵 (화면: ${if (isScreenOn) "ON" else "OFF"}, 일시정지: $isPaused)")
                        continue
                    }
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val elapsedSec = ((nowElapsed - localLastTick) / 1000L).coerceAtLeast(0L)
                    localLastTick = nowElapsed
                    lastTickElapsedRealtime = nowElapsed

                    val secondsToApply = minOf(elapsedSec, TICK_INTERVAL_SECONDS)
                    if (secondsToApply <= 0L) continue

                    if (!preferenceManager.isCreditSessionActive()) {
                        val accumulated = preferenceManager.getAccumulatedAbstentionSeconds()
                        preferenceManager.setAccumulatedAbstentionSeconds(accumulated + secondsToApply)
                        preferenceManager.setLastMiningTime(System.currentTimeMillis())
                        Log.d(TAG, "절제 시간 누적: +${secondsToApply}초 (총 ${accumulated + secondsToApply}초)")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tick loop", e)
                }
            }
        }
    }

    /** Screen OFF during Credit Session: no deduction here; single deduction at session end. */
    private fun performFinalDeductionOnScreenOff() {
        if (!preferenceManager.isCreditSessionActive() || isPaused) return
        preferenceManager.setLastKnownAliveSessionTime(SystemClock.elapsedRealtime())
    }

    /**
     * 중간 정산: 화면 OFF 시점(또는 세션 시작)부터 현재까지 사용분을 차감하고, startTime을 현재로 갱신하여
     * 이후 performFinalDeductionOnSessionEnd의 usage = (endTime - startTime)이 중간 정산 이후 구간만 포함하도록 합니다.
     */
    private fun settleCreditsMidSession() {
        if (!preferenceManager.isCreditSessionActive()) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val sessionStartElapsed = preferenceManager.getCreditSessionStartElapsedRealtime()
        val lastScreenOffElapsed = preferenceManager.getLastScreenOffElapsedRealtime()
        val fromElapsed = maxOf(sessionStartElapsed, lastScreenOffElapsed.coerceAtLeast(0L))
        if (fromElapsed <= 0L) return
        val usageSeconds = ((nowElapsed - fromElapsed) / 1000L).coerceAtLeast(0L)
        val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
        val cappedUsage = if (creditAtStart > 0L && usageSeconds > creditAtStart) creditAtStart else usageSeconds
        val cappedUsage2 = minOf(cappedUsage, MAX_SESSION_DURATION_SECONDS)
        val balanceBefore = preferenceManager.getTimeCreditBalanceSeconds()
        val actualDeduct = minOf(cappedUsage2, balanceBefore)
        if (actualDeduct > 0L) {
            val balanceAfter = (balanceBefore - actualDeduct).coerceAtLeast(0L)
            preferenceManager.setTimeCreditBalanceSeconds(balanceAfter)
            Log.d(TAG, "중간 정산: usage=${cappedUsage2}s 차감=${actualDeduct}s 잔액=${balanceBefore}s→${balanceAfter}s")
        }
        preferenceManager.setCreditSessionStartTime(System.currentTimeMillis())
        preferenceManager.setCreditSessionStartElapsedRealtime(nowElapsed)
        TimeCreditService.sessionStartElapsedRealtime = nowElapsed
        preferenceManager.setLastKnownAliveSessionTime(nowElapsed)
        preferenceManager.persistTimeCreditValues()
        val remaining = preferenceManager.getTimeCreditBalanceSeconds()
        cancelExhaustionTimer()
        if (remaining > 0L) scheduleExhaustionTimer(remaining)
    }

    /**
     * 화면 OFF 상태에서 차단 앱 오디오가 재생 중이면 1초 주기로 잔액 차감.
     * 오디오 중단 시 루프 종료(세션 유지). 화면 ON 시 stopScreenOffDeductionJob()으로 취소.
     */
    private fun startScreenOffDeductionJobIfNeeded() {
        synchronized(syncStateLock) {
            // 이미 실행 중이면 스킵
            if (screenOffDeductionJob?.isActive == true) {
                Log.d(TAG, "화면 OFF 차감 job 이미 실행 중 → 스킵")
                return
            }
            
            if (!preferenceManager.isCreditSessionActive()) return
            val pkg = preferenceManager.getCreditSessionPackage() ?: return
            if (!computeCurrentAudioStatusInternal(pkg)) return
            
            // 기존 job 취소 및 완료 대기
            screenOffDeductionJob?.cancel()
            screenOffDeductionJob = null
            
            screenOffDeductionJob = serviceScope.launch {
            var tickCount = 0
            Log.d(TAG, "화면 OFF 오디오 재생: 1초 차감 시작 (pkg=$pkg)")
            while (isActive) {
                try {
                    delay(1000L)
                    if (!preferenceManager.isCreditSessionActive()) break
                    val balance = preferenceManager.getTimeCreditBalanceSeconds()
                    if (evaluateCreditAction(balanceAfter = balance) is CreditAction.ExhaustAndNotify) {
                        Log.d(TAG, "화면 OFF 차감: 잔액 0 도달, 세션 종료")
                        notifyCreditExhausted()
                        break
                    }
                    if (!computeCurrentAudioStatusInternal(pkg)) {
                        Log.d(TAG, "화면 OFF 차감: 오디오 중단, 차감 중지 (C=${balance}s)")
                        break
                    }
                    val newBalance = (balance - 1L).coerceAtLeast(0L)
                    preferenceManager.setTimeCreditBalanceSeconds(newBalance)
                    val nowElapsed = SystemClock.elapsedRealtime()
                    preferenceManager.setLastKnownAliveSessionTime(nowElapsed)
                    preferenceManager.setLastKnownBalanceAtAlive(newBalance)
                    tickCount++
                    if (tickCount % 5 == 0) {
                        preferenceManager.persistTimeCreditValues()
                    }
                    if (evaluateCreditAction(balanceAfter = newBalance) is CreditAction.ExhaustAndNotify) {
                        notifyCreditExhausted()
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Screen-off deduction tick 오류", e)
                }
            }
            screenOffDeductionJob = null
        }
    }

    /**
     * 1초 차감 job 중단. 화면 ON 시 호출되며, job이 돌고 있었으면 lastSync를 현재로 갱신하여
     * 이후 syncState()가 화면 OFF~ON 구간을 이중 차감하지 않도록 한다.
     */
    private fun stopScreenOffDeductionJob() {
        if (screenOffDeductionJob != null && preferenceManager.isCreditSessionActive()) {
            preferenceManager.setTimeCreditLastSyncTime(SystemClock.elapsedRealtime())
        }
        screenOffDeductionJob?.cancel()
        screenOffDeductionJob = null
    }

    internal fun clearExhaustionGuard() {
        creditExhaustionInProgress = false
    }

    internal fun setLastKnownForegroundPackageInternal(packageName: String?) {
        lastKnownForegroundPackage = packageName
        // Phase 1: 상태 영속화 - 메모리 업데이트 시 디스크에도 저장
        preferenceManager.setLastKnownForegroundPackage(packageName)
    }

    /**
     * 차단 앱 이탈 시 잔액이 있으면 즉시 세션 종료 대신 휴면(Dormant) 진입.
     * 이미 휴면(lastKnown != sessionPackage)이면 정산 없이 갱신·알람만 취소. 순서: 판단 후 갱신.
     */
    internal fun requestDormantInternal(targetPackageName: String) {
        serviceScope.launch(Dispatchers.Default) {
            synchronized(syncStateLock) {
                if (!preferenceManager.isCreditSessionActive()) return@launch
                val balance = preferenceManager.getTimeCreditBalanceSeconds()
                if (balance <= 0L) return@launch
                val sessionPackage = preferenceManager.getCreditSessionPackage()
                val wasAlreadyDormant = lastKnownForegroundPackage != null && lastKnownForegroundPackage != sessionPackage
                if (wasAlreadyDormant) {
                    lastKnownForegroundPackage = targetPackageName
                    cancelPrecisionTransitionAlarm()
                    cancelExhaustionTimer()
                    Log.d(TAG, "휴면 유지 → 정산 스킵 (target=$targetPackageName, C=${balance}s)")
                    return@launch
                }
                syncState("Dormant_Entry", balanceOverride = balance)
                lastKnownForegroundPackage = targetPackageName
                Log.d(TAG, "휴면(Dormant) 강제 진입 (target=$targetPackageName, C=${preferenceManager.getTimeCreditBalanceSeconds()}s), 세션 유지")
            }
        }
    }

    /**
     * Phase 4: 누적 합산 방식 전환 + 물리적 한계 검증 + 리부팅 대응
     * 벽시계 금지: (endElapsed - startElapsed) 사용 안 함
     * 누적 합산: creditAtStart - balanceBefore = 이미 깎인 시간
     * 마지막 구간 정산: lastSync ~ snapshotNow
     */
    private fun performFinalDeductionOnSessionEnd() {
        if (!preferenceManager.isCreditSessionActive()) return
        
        // 벽시계 금지: (endElapsed - startElapsed) 사용 안 함
        // 누적 합산: creditAtStart - balanceBefore = 이미 깎인 시간
        val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
        val balanceBefore = preferenceManager.getTimeCreditBalanceSeconds()
        val accumulatedUsage = (creditAtStart - balanceBefore).coerceAtLeast(0L)

        // 마지막 구간 정산 (lastSync ~ snapshotNow)
        val snapshotNow = timeSource.elapsedRealtime()
        val lastSync = preferenceManager.getTimeCreditLastSyncTime()
        val sessionStart = preferenceManager.getCreditSessionStartElapsedRealtime()
        
        // 리부팅 감지: 저장된 시간이 현재보다 크면 재부팅 발생
        if (lastSync > snapshotNow || sessionStart > snapshotNow) {
            Log.w(TAG, "${AppLog.CONSISTENCY} Reboot detected in final deduction (lastSync=$lastSync, sessionStart=$sessionStart > now=$snapshotNow), skipping final segment")
            // 재부팅 후에는 마지막 구간 정산 스킵 (이미 누적 합산으로 처리됨)
            // balance는 그대로 유지하고 lastSync만 현재 시간으로 갱신하여 정합성 유지
            preferenceManager.persistBalanceAndLastSyncTime(balanceBefore, snapshotNow, synchronous = true)
            stopSessionMonitor()
            return
        }
        
        // 세션 종료 시에는 lastSync > 0L만 확인 (lastKnownForegroundPackage는 체크하지 않음)
        val finalSegmentUsage = if (lastSync > 0L) {
            ((snapshotNow - lastSync) / 1000L).coerceAtLeast(0L)
        } else 0L

        // 물리적 한계 검증 (Safety Net): Time Travel 방지
        val physicalTimeLimit = if (sessionStart > 0L) {
            (snapshotNow - sessionStart) / 1000L
        } else Long.MAX_VALUE  // sessionStart가 없으면 물리적 한계 적용 안 함
        
        // 세이프 가드 발동 감지: finalSegmentUsage가 physicalTimeLimit보다 크면 발동
        if (finalSegmentUsage > physicalTimeLimit) {
            physicalLimitGuardTriggerCount.incrementAndGet()
            Log.w(TAG, "${AppLog.CONSISTENCY} Physical limit guard triggered: calculated=$finalSegmentUsage, limit=$physicalTimeLimit [triggerCount=${physicalLimitGuardTriggerCount.get()}]")
        }
        
        val usageToDeduct = minOf(finalSegmentUsage, physicalTimeLimit, balanceBefore)
        val balanceAfter = (balanceBefore - usageToDeduct).coerceAtLeast(0L)

        // 원자적 저장 (세션 종료 시에는 synchronous=true로 정합성 보장)
        preferenceManager.persistBalanceAndLastSyncTime(balanceAfter, snapshotNow, synchronous = true)
        
        Log.d(TAG, "${AppLog.CONSISTENCY} Final deduction: accumulated=$accumulatedUsage, finalSegment=$finalSegmentUsage, physicalLimit=$physicalTimeLimit, deducted=$usageToDeduct, balance=$balanceBefore→$balanceAfter")
        stopSessionMonitor()
    }

    private fun scheduleExhaustionTimer(balanceSeconds: Long) {
        if (balanceSeconds <= 0L) return
        cancelExhaustionTimer()
        val delayMs = balanceSeconds * 1000L
        exhaustionHandler.postDelayed(exhaustionRunnable, delayMs)
        Log.d(TAG, "${AppLog.CREDIT} exhaustion timer scheduled → in ${balanceSeconds}s (balance=$balanceSeconds)")
    }

    private fun cancelExhaustionTimer() {
        exhaustionHandler.removeCallbacks(exhaustionRunnable)
    }

    /** 정밀 감시 전환 알람 취소. 중복 실행 방지. */
    private fun cancelPrecisionTransitionAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, TimeCreditBackgroundService::class.java).apply {
            action = TimeCreditService.ACTION_PRECISION_TRANSITION
        }
        val pending = PendingIntent.getService(
            this,
            REQUEST_CODE_PRECISION_TRANSITION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pending)
    }

    /** 정밀 감시 전환 알람 예약. delaySeconds 후 syncState() 실행. */
    private fun schedulePrecisionTransitionAlarm(delaySeconds: Long) {
        if (delaySeconds <= 0L) return
        cancelPrecisionTransitionAlarm()
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, TimeCreditBackgroundService::class.java).apply {
            action = TimeCreditService.ACTION_PRECISION_TRANSITION
        }
        val pending = PendingIntent.getService(
            this,
            REQUEST_CODE_PRECISION_TRANSITION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + delaySeconds * 1000L
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            }
            Log.d(TAG, "Precision transition alarm scheduled: ${delaySeconds}s 후")
        } catch (e: Exception) {
            Log.e(TAG, "Precision transition alarm schedule failed", e)
        }
    }

    /**
     * 마지막 동기화 시점(lastSync 또는 세션 시작 elapsed)부터 현재까지 경과 초를 사용량으로 계산.
     * Phase 3: 시그니처 변경 - snapshotNow 파라미터로 시간 소스 추상화 및 리부팅 감지 추가.
     * 
     * @param snapshotNow 이벤트 진입 시점의 단일 스냅샷 (elapsedRealtime 기준)
     */
    private fun calculateUsageSinceLastSync(snapshotNow: Long): Long {
        val lastSync = preferenceManager.getTimeCreditLastSyncTime()
        
        // 리부팅 감지: lastSync가 현재 시간보다 크면 재부팅 발생
        if (lastSync > snapshotNow) {
            Log.w(TAG, "${AppLog.CONSISTENCY} Reboot detected in calculateUsage (lastSync=$lastSync > now=$snapshotNow), returning 0")
            return 0L
        }
        
        val fromElapsed = if (lastSync > 0L) lastSync else preferenceManager.getCreditSessionStartElapsedRealtime()
        if (fromElapsed <= 0L) return 0L
        
        // 리부팅 감지: fromElapsed도 체크
        if (fromElapsed > snapshotNow) {
            Log.w(TAG, "${AppLog.CONSISTENCY} Reboot detected (fromElapsed=$fromElapsed > now=$snapshotNow), returning 0")
            return 0L
        }
        
        val usageSeconds = ((snapshotNow - fromElapsed) / 1000L).coerceAtLeast(0L)
        val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
        val capped = if (creditAtStart > 0L && usageSeconds > creditAtStart) creditAtStart else usageSeconds
        return minOf(capped, MAX_SESSION_DURATION_SECONDS)
    }

    /**
     * 구간 판정: Exhausted(C≤0) / Safe(C>0). syncStateLock 보유 상태에서만 호출.
     * ExhaustAndNotify 반환 시 syncState가 락 해제 후 notifyCreditExhausted() 호출(데드락 방지).
     */
    private fun handleZoneTransition(balance: Long, isDoze: Boolean = false): CreditAction {
        return when {
            balance <= 0L -> {
                cancelPrecisionTransitionAlarm()
                CreditAction.ExhaustAndNotify
            }
            else -> {
                cancelExhaustionTimer()
                schedulePrecisionTransitionAlarm(balance)
                if (!isDoze) Log.d(TAG, "syncState: 잔액 유지, 정밀 전환 알람 ${balance}s 후 (C=${balance}s)")
                else if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "syncState: Doze 구간 (C=${balance}s)")
                CreditAction.NoOp
            }
        }
    }

    /**
     * 적응형 감시: 세션 중 상태 동기화. 정산 → 잔액 갱신 → handleZoneTransition.
     * Phase 3: 스냅샷 도입 및 멱등성 가드, 리부팅 대응 추가.
     * Phase 5: 상태 전이 로깅 추가 (best-effort).
     * 
     * @param reason "Dormant_Entry"이면 차단 앱 이탈 직전 사용분 정산 후 lastSync 갱신·알람 취소(휴면).
     * @param balanceOverride Dormant_Entry 시 호출부에서 넘긴 잔액(로그용). 정산은 lastSync~now 구간으로 수행.
     * @param snapshotNow 이벤트 진입 시점의 단일 스냅샷 (테스트에서 주입 가능)
     * @param synchronous true인 경우 commit() 사용 (정합성이 중요한 경로: 화면 OFF, 세션 종료 등)
     */
    private fun syncState(reason: String? = null, balanceOverride: Long? = null, snapshotNow: Long = timeSource.elapsedRealtime(), synchronous: Boolean = false) {
        if (!preferenceManager.isCreditSessionActive()) return
        
        // Phase 5: 상태 전이 로깅 (best-effort: 화면 ON 등에서는 포그라운드 갱신 시점에 따라 NextState가 한 프레임 뒤에 맞춰질 수 있음)
        val prevState = determineCurrentState()
        val eventDescription = when {
            reason == "Dormant_Entry" -> "DormantEntry"
            else -> "SyncState"
        }
        
        var zoneAction: CreditAction = CreditAction.NoOp
        synchronized(syncStateLock) {
            if (!preferenceManager.isCreditSessionActive()) return
            
            // 락 내부에서 시간 읽기 (TOCTOU 방지)
            val snapshotNowLocked = snapshotNow  // 락 내부에서 사용할 스냅샷
            val lastSyncTime = preferenceManager.getTimeCreditLastSyncTime()
            
            // 시스템 리부팅 감지: snapshotNow가 lastSyncTime보다 작으면 리부팅 발생
            if (snapshotNowLocked < lastSyncTime) {
                Log.w(TAG, "${AppLog.CONSISTENCY} Reboot detected (lastSync=$lastSyncTime > now=$snapshotNowLocked), resetting lastSync")
                preferenceManager.setTimeCreditLastSyncTime(0L)
                // 재부팅 후에는 정산 수행 (멱등성 가드 스킵)
            } else if (snapshotNowLocked == lastSyncTime) {
                // 멱등성 가드: 정확히 같은 시점의 중복 이벤트 스킵
                idempotencySkipCount.incrementAndGet()
                Log.d(TAG, "${AppLog.CONSISTENCY} Idempotency guard - duplicate event (snapshot=$snapshotNowLocked == lastSync=$lastSyncTime) [skipCount=${idempotencySkipCount.get()}]")
                return@synchronized
            }
            // snapshotNowLocked > lastSyncTime: 정상 정산 구간
            
            val isDoze = !isDisplayStateInteractive()

            if (reason == "Dormant_Entry" && balanceOverride != null) {
                // 첫 이탈만 여기 진입(requestDormantInternal에서 이미 휴면이면 호출 안 함). 차단 앱에서 쓴 시간 정산
                val usageSeconds = calculateUsageSinceLastSync(snapshotNowLocked)
                val balanceBefore = preferenceManager.getTimeCreditBalanceSeconds()
                val actualDeduct = minOf(usageSeconds, balanceBefore)
                if (actualDeduct > 0L) {
                    val balanceAfter = (balanceBefore - actualDeduct).coerceAtLeast(0L)
                    preferenceManager.setTimeCreditBalanceSeconds(balanceAfter)
                    if (balanceAfter == 60L) Log.d(TAG, "[디버그] 크레딧 60초 남음")
                    if (balanceAfter == 10L) Log.d(TAG, "[디버그] 크레딧 10초 남음")
                    if (!isDoze) Log.d(TAG, "syncState(휴면 진입) 정산: usage=${usageSeconds}s 차감=${actualDeduct}s 잔액=${balanceBefore}s→${balanceAfter}s")
                    else if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "syncState(휴면 진입) 정산(Doze): usage=${usageSeconds}s 잔액=${balanceAfter}s")
                }
                // Phase 3: 휴면 구간 닫기(Sealing) - usage=0이어도 lastSyncTime 갱신 필수, 원자적 저장 사용
                val balanceAfter = balanceBefore - actualDeduct
                preferenceManager.persistBalanceAndLastSyncTime(balanceAfter, snapshotNowLocked, synchronous = synchronous)
                preferenceManager.setLastKnownAliveSessionTime(snapshotNowLocked)
                cancelPrecisionTransitionAlarm()
                cancelExhaustionTimer()
                Log.d(TAG, "${AppLog.CONSISTENCY} Dormant entry: Sealing previous usage segment (lastSync=$snapshotNowLocked)")
                val C = preferenceManager.getTimeCreditBalanceSeconds()
                if (!isDoze) Log.d(TAG, "syncState: 휴면 진입, 정밀/소진 알람 취소 (C=${C}s)")
                return@synchronized
            }

            // 일반 정산 분기
            val sessionPkg = preferenceManager.getCreditSessionPackage()
            val usageSeconds = if (sessionPkg == null || lastKnownForegroundPackage != sessionPkg) {
                0L  // 휴면으로 간주
            } else {
                calculateUsageSinceLastSync(snapshotNowLocked)
            }
            val balanceBefore = preferenceManager.getTimeCreditBalanceSeconds()
            val actualDeduct = minOf(usageSeconds, balanceBefore)
            if (actualDeduct > 0L) {
                val balanceAfter = (balanceBefore - actualDeduct).coerceAtLeast(0L)
                preferenceManager.setTimeCreditBalanceSeconds(balanceAfter)
                if (balanceAfter == 60L) Log.d(TAG, "[디버그] 크레딧 60초 남음")
                if (balanceAfter == 10L) Log.d(TAG, "[디버그] 크레딧 10초 남음")
                if (!isDoze) Log.d(TAG, "syncState 정산: usage=${usageSeconds}s 차감=${actualDeduct}s 잔액=${balanceBefore}s→${balanceAfter}s")
                else if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "syncState 정산(Doze): usage=${usageSeconds}s 잔액=${balanceAfter}s")
            }
            
            // Phase 3: 원자적 저장 - balance와 lastSyncTime을 함께 저장
            val balanceAfter = balanceBefore - actualDeduct
            preferenceManager.persistBalanceAndLastSyncTime(balanceAfter, snapshotNowLocked, synchronous = synchronous)
            preferenceManager.setLastKnownAliveSessionTime(snapshotNowLocked)

            val C = preferenceManager.getTimeCreditBalanceSeconds()
            zoneAction = handleZoneTransition(C, isDoze)
        }
        
        // Phase 5: 상태 전이 로깅 (best-effort)
        val nextState = determineCurrentState()
        Log.d(TAG, "${AppLog.CONSISTENCY} State transition: PrevState=$prevState, Event=$eventDescription, NextState=$nextState")
        
        if (zoneAction is CreditAction.ExhaustAndNotify) notifyCreditExhausted()
    }

    private fun runExhaustionTimer() {
        if (!preferenceManager.isCreditSessionActive()) return
        val balance = preferenceManager.getTimeCreditBalanceSeconds()
        if (evaluateCreditAction(balanceAfter = balance) is CreditAction.ExhaustAndNotify) {
            notifyCreditExhausted()
        }
    }

    /** End session, cancel timer, notify callback for immediate block. (10분 쿨타임 기능 제거) */
    private fun notifyCreditExhausted() {
        if (!preferenceManager.isCreditSessionActive()) return
        synchronized(exhaustionLock) {
            if (creditExhaustionInProgress) return
            creditExhaustionInProgress = true
        }
        try {
            val packageName = preferenceManager.getCreditSessionPackage()
            val pkg = packageName ?: ""

            // Termination Bridge: 크레딧 0 도달 시 항상 오디오 포커스 강탈로 백그라운드 미디어 즉시 중단
            if (pkg.isNotBlank()) {
                PenaltyService(applicationContext).executeForcedMediaTermination(pkg)
            }
            // Background Phase: Credit 0 & Screen OFF → Kill Audio 후 채굴 재개 시점 기록. Screen ON 시 이 시점~ON까지 절제로 정산.
            if (!isScreenOn) {
                val now = SystemClock.elapsedRealtime()
                preferenceManager.setLastMiningResumeElapsedRealtime(now)
                Log.d(TAG, "Credit 0 + Screen OFF: Mining resume 시점 기록 (elapsed=$now)")
            }

            cancelExhaustionTimer()
            preferenceManager.setLastKnownBalanceAtAlive(0L)
            performFinalDeductionOnSessionEnd()
            timeCreditService.endCreditSession()
            Log.w(TAG, "${AppLog.CREDIT} session exhausted → notify block overlay immediately (package=$packageName)")
            packageName?.let { fireCreditExhaustedCallback(it) }
        } finally {
            creditExhaustionInProgress = false
        }
    }

    /**
     * 지능형 디스플레이 가드: Doze(3)/Doze_Suspend(4)는 '가짜 깨어남'으로 간주하고 false.
     * STATE_ON(2) 또는 STATE_OFF(1)일 때만 true (실제 화면 ON).
     */
    private fun isDisplayStateInteractive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return true
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return true
            @Suppress("DEPRECATION")
            val state = display.state
            if (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) {
                Log.v(TAG, "Display Guard: state=$state(Doze/DozeSuspend) 무시")
                false
            } else true
        } catch (e: Exception) {
            Log.e(TAG, "Display state check failed", e)
            true
        }
    }

    private fun registerScreenEventReceiver() {
        screenEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        if (!isDisplayStateInteractive()) {
                            Log.d(TAG, "Screen ON 무시: Doze 과도기 상태 (START_FOREGROUND 방지)")
                            return@onReceive
                        }
                        stopScreenOffDeductionJob()
                        isScreenOn = true
                        Log.i(TAG, "${AppLog.CREDIT} screen ON → settle abstention + resume timer")
                        runBlocking(Dispatchers.Default) {
                            calculateAccumulatedAbstention()
                            timeCreditService.settleCredits()
                            if (preferenceManager.isCreditSessionActive()) syncState()
                        }
                        screenOnSettlementDoneCallback?.invoke()
                        if (!preferenceManager.isCreditSessionActive()) startTickJob()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        // (1) 화면 OFF 진입점 단일화: candidatePackage 조회(동기), 즉시 ABS 콜백(도주·오버레이), 이후 TCB 정산·차감·잡.
                        val candidatePackage = screenOffAudioCandidateProvider?.invoke()
                        screenOffEventImmediateCallback?.invoke()
                        performFinalDeductionOnScreenOff()
                        preferenceManager.persistTimeCreditValues()
                        isScreenOn = false
                        tickJob?.cancel()
                        tickJob = null
                        stopSessionMonitor()
                        preferenceManager.setLastScreenOffTime(System.currentTimeMillis())
                        preferenceManager.setLastScreenOffElapsedRealtime(SystemClock.elapsedRealtime())
                        if (preferenceManager.isCreditSessionActive()) {
                            // Phase 3: 화면 OFF 경로는 정합성이 중요하므로 commit() 사용
                            runBlocking(Dispatchers.Default) { syncState(synchronous = true) }
                            startScreenOffDeductionJobIfNeeded()
                        }
                        Log.i(TAG, "${AppLog.CREDIT} screen OFF → final deduct + persist, timer/monitor stopped")
                        // (4) 200ms 후 오디오 확정: TCB가 저장 후 audioResolved 콜백 호출.
                        exhaustionHandler.postDelayed({
                            val audioBlocked = computeAudioBlockedOnScreenOff(candidatePackage)
                            preferenceManager.setAudioBlockedOnScreenOff(audioBlocked)
                            screenOffEventAudioResolvedCallback?.invoke(audioBlocked)
                        }, 200L)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenEventReceiver, filter)
    }

    private fun unregisterScreenEventReceiver() {
        screenEventReceiver?.let {
            try {
                unregisterReceiver(it)
                screenEventReceiver = null
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen event receiver", e)
            }
        }
    }

    /**
     * 화면 OFF→ON 시 절제 분 누적. 단조 시간 기반 계산. 보안: isPaused, checkBlockedAppAudio 체크.
     * P-4: Credit Session 활성 시 OFF 구간만큼 잔액 차감 보정.
     */
    private suspend fun calculateAccumulatedAbstention() {
        if (isPaused) {
            Log.d(TAG, "차단 앱 사용 중 화면 OFF -> 정산 제외")
            return
        }
        if (checkBlockedAppAudio()) {
            Log.d(TAG, "차단 앱 오디오 재생 감지 -> 정산 제외")
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        // Logic Sync: Credit 0 + Screen OFF에서 오디오 킬 후 재개 시점이 있으면 그 시점~Screen ON까지만 절제로 정산.
        val miningResumeElapsed = preferenceManager.getLastMiningResumeElapsedRealtime()
        val savedElapsed = if (miningResumeElapsed > 0L) {
            preferenceManager.setLastMiningResumeElapsedRealtime(0L)
            Log.d(TAG, "calculateAccumulatedAbstention: Audio Kill~Screen ON 구간 사용 (miningResumeElapsed=$miningResumeElapsed)")
            miningResumeElapsed
        } else {
            preferenceManager.getLastScreenOffElapsedRealtime()
        }
        if (savedElapsed <= 0L) {
            Log.d(TAG, "calculateAccumulatedAbstention: No previous screen off elapsed, skipping")
            preferenceManager.setLastScreenOnTime(System.currentTimeMillis())
            return
        }
        if (savedElapsed > nowElapsed) {
            Log.d(TAG, "calculateAccumulatedAbstention: 재부팅 감지 (savedElapsed > nowElapsed), 스킵")
            preferenceManager.setLastScreenOffElapsedRealtime(0L)
            preferenceManager.setLastScreenOnTime(System.currentTimeMillis())
            return
        }

        val elapsedSec = (nowElapsed - savedElapsed) / 1000L
        var offDurationSeconds = elapsedSec.coerceAtLeast(0L)
        if (offDurationSeconds > MAX_OFF_DURATION_SECONDS_CAP) {
            offDurationSeconds = MAX_OFF_DURATION_SECONDS_CAP
        }

        val endTime = System.currentTimeMillis()
        if (offDurationSeconds > 0L) {
            if (preferenceManager.isCreditSessionActive()) {
                preferenceManager.setLastScreenOnTime(endTime)
                preferenceManager.setLastScreenOffElapsedRealtime(0L)
                return
            } else {
                val accumulated = preferenceManager.getAccumulatedAbstentionSeconds()
                preferenceManager.setAccumulatedAbstentionSeconds(accumulated + offDurationSeconds)
                Log.d(TAG, "절제 시간 누적 (화면 OFF 구간): +${offDurationSeconds}초 (총 ${accumulated + offDurationSeconds}초)")
            }
        }
        preferenceManager.setLastScreenOnTime(endTime)
        preferenceManager.setLastScreenOffElapsedRealtime(0L)
    }

    private fun startAudioMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            stopAudioMonitoring()
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    serviceScope.launch {
                        checkBlockedAppAudioFromConfigs(configs)
                    }
                }
            }
            audioPlaybackCallback = callback
            audioManager.registerAudioPlaybackCallback(callback, null)
            serviceScope.launch {
                checkInitialAudioState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio monitoring", e)
        }
    }

    private fun stopAudioMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            audioPlaybackCallback?.let {
                audioManager.unregisterAudioPlaybackCallback(it)
                audioPlaybackCallback = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio monitoring", e)
        }
    }

    private suspend fun checkInitialAudioState() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeConfigs = audioManager.activePlaybackConfigurations
                if (activeConfigs.isNotEmpty()) {
                    checkBlockedAppAudioFromConfigs(activeConfigs)
                }
            } else {
                if (audioManager.isMusicActive) {
                    checkBlockedAppAudio()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check initial audio state", e)
        }
    }

    /**
     * 진입점 (4) 오디오 재생 상태 변경: 전처리(Persona 제외) → 정책(차단 앱 오디오 여부, 여기서 판단) → 액션(blockingServiceCallback → ABS.onAudioBlockStateChanged → transitionToState).
     */
    private suspend fun checkBlockedAppAudioFromConfigs(configs: List<AudioPlaybackConfiguration>) {
        try {
            if (AppBlockingService.isPersonaAudioPossiblyPlaying()) return  // 전처리: Persona 제외
            val hasActiveAudio = configs.isNotEmpty() && audioManager.isMusicActive

            if (preferenceManager.isCreditSessionActive()) {
                val sessionPkg = preferenceManager.getCreditSessionPackage()
                if (sessionPkg != null) {
                    val sessionAppAudioOn = computeCurrentAudioStatusInternal(sessionPkg)
                    if (sessionAppAudioOn) {
                        val screenOffOrNotSessionForeground = !isScreenOn || !isSessionAppInForeground(sessionPkg)
                        if (screenOffOrNotSessionForeground) {
                            startScreenOffDeductionJobIfNeeded()
                        }
                    } else {
                        stopScreenOffDeductionJob()
                    }
                }
            }

            if (!hasActiveAudio) {
                if (isPausedByAudio) {
                    isPausedByAudio = false
                    preferenceManager.setAudioBlockedOnScreenOff(false)
                    if (!preferenceManager.isCreditSessionActive()) {
                        blockingServiceCallback?.invoke(false)
                    }
                }
                return
            }
            val hasBlockedAppAudio = checkBlockedAppAudio()
            if (hasBlockedAppAudio && !isPausedByAudio) {
                isPausedByAudio = true
                blockingServiceCallback?.invoke(true)
            } else if (!hasBlockedAppAudio && isPausedByAudio) {
                isPausedByAudio = false
                preferenceManager.setAudioBlockedOnScreenOff(false)
                if (!preferenceManager.isCreditSessionActive()) {
                    blockingServiceCallback?.invoke(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[오디오 검사] 오류", e)
        }
    }

    private suspend fun checkBlockedAppAudio(): Boolean {
        return try {
            if (!audioManager.isMusicActive) return false
            val lastApp = preferenceManager.getLastMiningApp() ?: return false
            withContext(Dispatchers.IO) {
                database.appBlockDao().getBlockedApp(lastApp) != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkBlockedAppAudio 오류", e)
            false
        }
    }

    /**
     * UsageStatsManager로 "지금 화면에 떠 있는 앱" 패키지 조회.
     * 권한 없거나 실패 시 null 반환 → getLastMiningApp() 폴백 사용.
     */
    private fun getCurrentForegroundPackageInternal(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - 10_000L
            @Suppress("DEPRECATION")
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
                ?: return null
            val pkg = stats.asSequence()
                .filter { it.packageName != packageName }
                .maxByOrNull { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.lastTimeVisible else it.lastTimeUsed }
            pkg?.packageName
        } catch (e: Exception) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "UsageStats 포그라운드 조회 실패: ${e.message}")
            null
        }
    }

    /**
     * Credit Session 앱이 현재 포그라운드인지 여부.
     * Phase 2: 보수적 폴백 전략 - 엄격한 우선순위 적용.
     * 1순위: lastKnownForegroundPackage != sessionPackage → 100% 휴면
     * 2순위: 시스템 정보 (UsageStats)
     * 3순위: 보수적 폴백 - 모를 때는 false (Dormant로 간주)
     */
    private var lastFallbackLogTime: Long = 0L
    private val FALLBACK_LOG_INTERVAL_MS = 5000L
    
    private fun isSessionAppInForeground(sessionPackage: String): Boolean {
        // 1순위: lastKnownForegroundPackage != sessionPackage → 100% 휴면
        if (lastKnownForegroundPackage != null && lastKnownForegroundPackage != sessionPackage) {
            return false
        }
        // 2순위: 시스템 정보 (UsageStats)
        getCurrentForegroundPackageInternal()?.let { foreground ->
            return foreground == sessionPackage
        }
        // 3순위: 보수적 폴백 - 로그 빈도 제한
        val now = System.currentTimeMillis()
        if (now - lastFallbackLogTime > FALLBACK_LOG_INTERVAL_MS) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "${AppLog.CONSISTENCY} Cannot determine foreground app → conservative fallback: Dormant")
            }
            lastFallbackLogTime = now
        }
        return false
    }

    /**
     * 동기: 지정 패키지가 현재 오디오 재생 중인지 조회.
     * MediaSessionManager(API 21+)로 STATE_PLAYING 세션 확인, 불가 시 isMusicActive + lastMiningApp 폴백.
     */
    private fun computeCurrentAudioStatusInternal(packageName: String): Boolean {
        if (AppBlockingService.isPersonaAudioPossiblyPlaying()) return false
        
        // 1순위: MediaSessionManager (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return fallbackCurrentAudioStatus(packageName)
                // getActiveSessions(ComponentName) requires NotificationListenerService; without it returns empty or throws
                val component = ComponentName(this, TimeCreditBackgroundService::class.java)
                @Suppress("DEPRECATION")
                val controllers: List<MediaController> = sessionManager.getActiveSessions(component)
                for (controller in controllers) {
                    if (controller.packageName == packageName) {
                        val state = controller.playbackState
                        val stateCode = state?.state
                        val isPlaying = stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_BUFFERING
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "[오디오 스냅샷] MediaSessionManager: $packageName state=$stateCode (PLAYING/BUFFERING)")
                        }
                        return isPlaying
                    }
                }
            } catch (e: SecurityException) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "[오디오 스냅샷] MediaSessionManager 권한 없음, 폴백 사용")
                }
            } catch (e: Exception) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "[오디오 스냅샷] MediaSessionManager 실패: ${e.message}, 폴백 사용")
                }
            }
        }
        
        // 2순위: 폴백 (isMusicActive + lastMiningApp)
        return fallbackCurrentAudioStatus(packageName)
    }

    private fun fallbackCurrentAudioStatus(packageName: String): Boolean {
        val isMusicActive = audioManager.isMusicActive
        val lastApp = preferenceManager.getLastMiningApp()
        val match = isMusicActive && lastApp == packageName
        
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "[오디오 스냅샷] 폴백 진단: isMusicActive=$isMusicActive, lastApp=$lastApp, packageName=$packageName, match=$match")
        }
        
        return match
    }

    /** 위험 구간 첫 진입 시 1회: "1분 후 보상 종료" Grace Period 알림. */
    private fun showGracePeriod1MinNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "time_credit_grace_period",
                getString(R.string.notification_channel_time_credit),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "보상 시간 종료 임박 알림" }
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "time_credit_grace_period")
            .setContentTitle("보상 시간 종료 임박")
            .setContentText("보상 시간이 1분 후 종료됩니다!")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(6002, notification)
        Log.d(TAG, "Grace Period 알림 표시: 1분 후 종료")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_time_credit),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_time_credit_title))
            .setContentText(getString(R.string.notification_time_credit_title))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
