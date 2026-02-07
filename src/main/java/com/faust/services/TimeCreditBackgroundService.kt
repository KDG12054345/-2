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
    /** 골든 타임(종료 1분 전) 구간 전용 1초 주기 차감. 화면 OFF여도 동작합니다. */
    private var goldenTimeJob: Job? = null
    /** 화면 OFF 시 차단 앱 오디오 재생 중일 때만 동작하는 1초 주기 차감. */
    private var screenOffDeductionJob: Job? = null
    /** 골든 타임 진입 여부. true이면 소진 시 performFinalDeductionOnSessionEnd 생략(이미 1초 단위 차감됨). */
    private var isGoldenTimeMode = false
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

    /** 위험 구간 첫 진입 시 Grace Period "1분 후 종료" 알림 1회만 발송. */
    @Volatile
    private var dangerZoneGraceNotificationSent = false

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

        /** 1분 전 알람 수신 시 서비스를 깨워 골든 타임(중간 정산 + 1초 차감)을 진입시킵니다. 서비스가 이미 실행 중이어도 onStartCommand에 intent가 전달됩니다. */
        fun notifyGoldenTimeAlarm(context: Context) {
            val intent = Intent(context, TimeCreditBackgroundService::class.java).apply {
                action = TimeCreditService.ACTION_1MIN_BEFORE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Golden time alarm start 실패", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TimeCreditBackgroundService::class.java)
            context.stopService(intent)
        }

        fun pauseMining() {
            instance?.let {
                it.isPausedByApp = true
                Log.d(TAG, "[적립 중단] 앱 차단으로 인한 일시정지")
            }
        }

        fun resumeMining() {
            instance?.let {
                it.isPausedByApp = false
                Log.d(TAG, "[적립 재개] 앱 차단 해제로 인한 재개")
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
         * 세션은 유지하고 1초 차감만 중단합니다.
         */
        fun requestDormant() {
            instance?.requestDormantInternal()
        }

        /** Adaptive Monitoring: Credit Session 시작 시 구간 판정 및 알람/틱 설정. */
        fun onCreditSessionStarted() {
            instance?.syncState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() 호출")
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        preferenceManager.setServiceRunning(true)
        registerScreenEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            TimeCreditService.ACTION_PRECISION_TRANSITION -> {
                runBlocking(Dispatchers.Default) { syncState() }
                return START_STICKY
            }
            TimeCreditService.ACTION_1MIN_BEFORE -> {
                enterGoldenTimeFromAlarm()
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
        isGoldenTimeMode = false
        cancelExhaustionTimer()
        goldenTimeJob?.cancel()
        goldenTimeJob = null
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
     * If process was killed during an active session, recover by deducting (lastKnownAlive - startTime)
     * and ending the session so no credit is lost. 골든 타임 중 종료 시 체크포인트 잔액으로 복구(이중 차감 방지).
     */
    private fun tryRecoverSessionIfKilled() {
        if (!preferenceManager.isCreditSessionActive()) return
        if (preferenceManager.isGoldenTimeActive()) {
            val checkpointBalance = preferenceManager.getLastKnownBalanceAtAlive()
            preferenceManager.setTimeCreditBalanceSeconds(checkpointBalance)
            preferenceManager.persistTimeCreditValues()
            timeCreditService.endCreditSession()
            preferenceManager.setGoldenTimeActive(false)
            preferenceManager.setLastKnownAliveSessionTime(0L)
            preferenceManager.setLastKnownBalanceAtAlive(0L)
            Log.w(TAG, "Credit Session 복구 완료 (골든 타임 체크포인트 잔액=$checkpointBalance)")
            return
        }
        val lastAlive = preferenceManager.getLastKnownAliveSessionTime()
        if (lastAlive <= 0L) return
        val startElapsed = preferenceManager.getCreditSessionStartElapsedRealtime()
        if (startElapsed <= 0L) return
        var calculatedUsageSeconds = ((lastAlive - startElapsed) / 1000L).coerceAtLeast(0L)
        val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
        if (creditAtStart > 0L && calculatedUsageSeconds > creditAtStart) {
            calculatedUsageSeconds = creditAtStart
        }
        if (calculatedUsageSeconds > MAX_SESSION_DURATION_SECONDS) {
            calculatedUsageSeconds = MAX_SESSION_DURATION_SECONDS
        }
        val balanceBefore = preferenceManager.getTimeCreditBalanceSeconds()
        val actualDeduct = minOf(calculatedUsageSeconds, balanceBefore)
        if (actualDeduct > 0L) {
            val balanceAfter = (balanceBefore - actualDeduct).coerceAtLeast(0L)
            preferenceManager.setTimeCreditBalanceSeconds(balanceAfter)
            Log.d(TAG, "Recovery settlement: calculatedUsage=${calculatedUsageSeconds}s balanceBefore=${balanceBefore}s balanceAfter=${balanceAfter}s deducted=${actualDeduct}s")
        }
        preferenceManager.persistTimeCreditValues()
        timeCreditService.endCreditSession()
        preferenceManager.setLastKnownAliveSessionTime(0L)
        Log.w(TAG, "Credit Session 복구 완료 (프로세스 비정상 종료 후)")
    }

    private fun startSessionMonitor() {
        sessionMonitorJob?.cancel()
        sessionMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(SESSION_MONITOR_INTERVAL_MS)
                    if (!preferenceManager.isCreditSessionActive()) continue
                    val nowElapsed = SystemClock.elapsedRealtime()
                    preferenceManager.setLastKnownAliveSessionTime(nowElapsed)
                    val startElapsed = preferenceManager.getCreditSessionStartElapsedRealtime()
                    val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
                    if (startElapsed <= 0L || creditAtStart <= 0L) continue
                    val durationSec = (nowElapsed - startElapsed) / 1000L
                    if (durationSec >= creditAtStart) {
                        Log.d(TAG, "Session monitor: duration ${durationSec}s >= credit ${creditAtStart}s, triggering exhaustion")
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
     * 1분 전 알람 수신 시: 중간 정산 후 골든 타임(1초 차감) 진입. 화면 OFF여도 1초 차감이 동작합니다.
     */
    private fun enterGoldenTimeFromAlarm() {
        if (!preferenceManager.isCreditSessionActive()) {
            Log.d(TAG, "Golden time 알람 무시: Credit Session 비활성")
            return
        }
        stopSessionMonitor()
        isGoldenTimeMode = true
        preferenceManager.setGoldenTimeActive(true)
        settleCreditsMidSession()
        startGoldenTimeJob()
        Log.d(TAG, "골든 타임 진입: 중간 정산 완료, 1초 차감 시작")
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

    /** 체크포인트: 골든 타임에서 전체 persist 주기(초). 매 틱 디스크 I/O 완화. */
    private val GOLDEN_TIME_PERSIST_INTERVAL_TICKS = 5

    /** 골든 타임 전용 1초 주기 차감. 오디오 재생 OR 차단 앱 포그라운드 중 하나라도 해당하면 계속 차감; 둘 다 아니면 휴면(Dormant). */
    private fun startGoldenTimeJob() {
        goldenTimeJob?.cancel()
        goldenTimeJob = serviceScope.launch {
            var tickCount = 0
            while (isActive) {
                try {
                    delay(1000L)
                    if (!preferenceManager.isCreditSessionActive()) break
                    val pkg = preferenceManager.getCreditSessionPackage()
                    val balance = preferenceManager.getTimeCreditBalanceSeconds()
                    if (balance <= 0L) {
                        Log.d(TAG, "골든 타임: 잔액 0 도달, 세션 종료")
                        notifyCreditExhausted()
                        break
                    }
                    val audioActive = pkg != null && computeCurrentAudioStatusInternal(pkg)
                    val appInForeground = pkg != null && isSessionAppInForeground(pkg)
                    if (pkg != null && !audioActive && !appInForeground) {
                        Log.d(TAG, "골든 타임: 오디오 중단·앱 이탈 감지, 휴면(Dormant) 진입 (C=${balance}s), 세션 유지")
                        syncState("Dormant_Entry", balanceOverride = balance)
                        goldenTimeJob = null
                        return@launch
                    }
                    val newBalance = (balance - 1L).coerceAtLeast(0L)
                    preferenceManager.setTimeCreditBalanceSeconds(newBalance)
                    val nowElapsed = SystemClock.elapsedRealtime()
                    preferenceManager.setLastKnownAliveSessionTime(nowElapsed)
                    preferenceManager.setLastKnownBalanceAtAlive(newBalance)
                    tickCount++
                    if (tickCount % GOLDEN_TIME_PERSIST_INTERVAL_TICKS == 0) {
                        preferenceManager.persistTimeCreditValues()
                    }
                    if (newBalance <= 0L) {
                        notifyCreditExhausted()
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Golden time tick 오류", e)
                }
            }
            goldenTimeJob = null
        }
    }

    /**
     * 화면 OFF 상태에서 차단 앱 오디오가 재생 중이면 1초 주기로 잔액 차감.
     * 오디오 중단 시 루프 종료(세션 유지). 화면 ON 시 stopScreenOffDeductionJob()으로 취소.
     */
    private fun startScreenOffDeductionJobIfNeeded() {
        if (!preferenceManager.isCreditSessionActive()) return
        val pkg = preferenceManager.getCreditSessionPackage() ?: return
        if (!computeCurrentAudioStatusInternal(pkg)) return
        screenOffDeductionJob?.cancel()
        screenOffDeductionJob = serviceScope.launch {
            var tickCount = 0
            Log.d(TAG, "화면 OFF 오디오 재생: 1초 차감 시작 (pkg=$pkg)")
            while (isActive) {
                try {
                    delay(1000L)
                    if (!preferenceManager.isCreditSessionActive()) break
                    val balance = preferenceManager.getTimeCreditBalanceSeconds()
                    if (balance <= 0L) {
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
                    if (tickCount % GOLDEN_TIME_PERSIST_INTERVAL_TICKS == 0) {
                        preferenceManager.persistTimeCreditValues()
                    }
                    if (newBalance <= 0L) {
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

    private fun stopScreenOffDeductionJob() {
        screenOffDeductionJob?.cancel()
        screenOffDeductionJob = null
    }

    /**
     * 차단 앱 이탈 시 잔액이 있으면 즉시 세션 종료 대신 휴면(Dormant) 진입.
     * 골든 타임 Job 중단, lastSync 갱신, 세션은 유지.
     */
    internal fun requestDormantInternal() {
        serviceScope.launch(Dispatchers.Default) {
            synchronized(syncStateLock) {
                if (!preferenceManager.isCreditSessionActive()) return@launch
                goldenTimeJob?.cancel()
                goldenTimeJob = null
                val balance = preferenceManager.getTimeCreditBalanceSeconds()
                if (balance <= 0L) return@launch
                syncState("Dormant_Entry", balanceOverride = balance)
                Log.d(TAG, "휴면(Dormant) 강제 진입 (C=${balance}s), 세션 유지")
            }
        }
    }

    /**
     * Session end: single deduction by session duration. totalSeconds = (endTime - startTime) / 1000, 1:1.
     * Patch: Validation and guardrails to prevent Abnormal Batch Deductions (e.g. sudden 602s drop from bad state).
     * - Usage = CurrentTime - SessionStartTime; anomaly cap (negative / max duration); balance protection min(usage, balance).
     * - State reset (startTime, session vars) is done by caller via TimeCreditService.endCreditSession() immediately after.
     */
    private fun performFinalDeductionOnSessionEnd() {
        if (!preferenceManager.isCreditSessionActive()) return
        val startElapsed = preferenceManager.getCreditSessionStartElapsedRealtime()
        val endElapsed = SystemClock.elapsedRealtime()
        if (startElapsed <= 0L) return
        var calculatedUsageSeconds = ((endElapsed - startElapsed) / 1000L).coerceAtLeast(0L)
        val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
        if (creditAtStart > 0L && calculatedUsageSeconds > creditAtStart) {
            Log.w(TAG, "Settlement guardrail: usage ${calculatedUsageSeconds}s capped to creditAtStart ${creditAtStart}s")
            calculatedUsageSeconds = creditAtStart
        }
        if (calculatedUsageSeconds > MAX_SESSION_DURATION_SECONDS) {
            Log.w(TAG, "Settlement guardrail: usage ${calculatedUsageSeconds}s capped to max ${MAX_SESSION_DURATION_SECONDS}s")
            calculatedUsageSeconds = MAX_SESSION_DURATION_SECONDS
        }
        val balanceBefore = preferenceManager.getTimeCreditBalanceSeconds()
        val actualDeduct = minOf(calculatedUsageSeconds, balanceBefore)
        if (actualDeduct > 0L) {
            val balanceAfter = (balanceBefore - actualDeduct).coerceAtLeast(0L)
            preferenceManager.setTimeCreditBalanceSeconds(balanceAfter)
            Log.d(TAG, "Settlement: calculatedUsage=${calculatedUsageSeconds}s balanceBefore=${balanceBefore}s balanceAfter=${balanceAfter}s deducted=${actualDeduct}s")
        }
        preferenceManager.persistTimeCreditValues()
        stopSessionMonitor()
    }

    private fun scheduleExhaustionTimer(balanceSeconds: Long) {
        if (balanceSeconds <= 0L) return
        cancelExhaustionTimer()
        val delayMs = balanceSeconds * 1000L
        exhaustionHandler.postDelayed(exhaustionRunnable, delayMs)
        Log.d(TAG, "Exhaustion timer scheduled: ${balanceSeconds}초 후")
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
     * SystemClock.elapsedRealtime() 기준으로 시계 조작 영향을 받지 않음.
     */
    private fun calculateUsageSinceLastSync(): Long {
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastSync = preferenceManager.getTimeCreditLastSyncTime()
        val fromElapsed = if (lastSync > 0L) lastSync else preferenceManager.getCreditSessionStartElapsedRealtime()
        if (fromElapsed <= 0L) return 0L
        val usageSeconds = ((nowElapsed - fromElapsed) / 1000L).coerceAtLeast(0L)
        val creditAtStart = preferenceManager.getCreditAtSessionStartSeconds()
        val capped = if (creditAtStart > 0L && usageSeconds > creditAtStart) creditAtStart else usageSeconds
        return minOf(capped, MAX_SESSION_DURATION_SECONDS)
    }

    /**
     * 구간 판정 및 동작: Safe(C>60) / Danger(0<C≤60) / Exhausted(C≤0).
     * syncStateLock 보유 상태에서만 호출할 것.
     * @param isDoze true이면 유예 알림·무거운 로직 생략(Doze 모드 최적화).
     */
    private fun handleZoneTransition(balance: Long, isDoze: Boolean = false) {
        when {
            balance <= 0L -> {
                cancelPrecisionTransitionAlarm()
                goldenTimeJob?.cancel()
                goldenTimeJob = null
                notifyCreditExhausted()
            }
            balance <= THRESHOLD_DANGER_ZONE_SECONDS -> {
                cancelPrecisionTransitionAlarm()
                cancelExhaustionTimer()
                if (!isDoze) {
                    if (!dangerZoneGraceNotificationSent) {
                        dangerZoneGraceNotificationSent = true
                        showGracePeriod1MinNotification()
                    }
                    if (goldenTimeJob?.isActive != true) {
                        isGoldenTimeMode = true
                        preferenceManager.setGoldenTimeActive(true)
                        startGoldenTimeJob()
                        Log.d(TAG, "syncState: 위험 구간 진입, 1초 정밀 감시 시작 (C=${balance}s)")
                    } else {
                        Unit
                    }
                } else {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "syncState: Doze 구간 Danger (C=${balance}s), 정밀 틱/알림 스킵")
                    }
                    schedulePrecisionTransitionAlarm(balance)
                }
            }
            else -> {
                dangerZoneGraceNotificationSent = false
                goldenTimeJob?.cancel()
                goldenTimeJob = null
                isGoldenTimeMode = false
                preferenceManager.setGoldenTimeActive(false)
                cancelExhaustionTimer()
                val x = balance - THRESHOLD_DANGER_ZONE_SECONDS
                schedulePrecisionTransitionAlarm(x)
                if (!isDoze) Log.d(TAG, "syncState: 안전 구간 유지, 정밀 전환 알람 ${x}s 후 (C=${balance}s)")
                else if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "syncState: Doze 안전 구간 (C=${balance}s)")
            }
        }
    }

    /**
     * 적응형 감시: 세션 중 상태 동기화. 정산 → 잔액 갱신 → handleZoneTransition.
     * @param reason "Dormant_Entry"이면 정산 생략, lastSync만 갱신 후 구간 판정(휴면 단일화).
     * @param balanceOverride Dormant_Entry 시 사용할 잔액(정산 없이 구간 판정만).
     */
    private fun syncState(reason: String? = null, balanceOverride: Long? = null) {
        if (!preferenceManager.isCreditSessionActive()) return
        synchronized(syncStateLock) {
            if (!preferenceManager.isCreditSessionActive()) return
            val isDoze = !isDisplayStateInteractive()

            if (reason == "Dormant_Entry" && balanceOverride != null) {
                val nowElapsed = SystemClock.elapsedRealtime()
                preferenceManager.setTimeCreditLastSyncTime(nowElapsed)
                preferenceManager.setLastKnownAliveSessionTime(nowElapsed)
                preferenceManager.persistTimeCreditValues()
                handleZoneTransition(balanceOverride, isDoze)
                return@synchronized
            }

            val usageSeconds = calculateUsageSinceLastSync()
            val balanceBefore = preferenceManager.getTimeCreditBalanceSeconds()
            val actualDeduct = minOf(usageSeconds, balanceBefore)
            if (actualDeduct > 0L) {
                val balanceAfter = (balanceBefore - actualDeduct).coerceAtLeast(0L)
                preferenceManager.setTimeCreditBalanceSeconds(balanceAfter)
                if (!isDoze) Log.d(TAG, "syncState 정산: usage=${usageSeconds}s 차감=${actualDeduct}s 잔액=${balanceBefore}s→${balanceAfter}s")
                else if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "syncState 정산(Doze): usage=${usageSeconds}s 잔액=${balanceAfter}s")
            }
            val nowElapsed = SystemClock.elapsedRealtime()
            preferenceManager.setTimeCreditLastSyncTime(nowElapsed)
            preferenceManager.setLastKnownAliveSessionTime(nowElapsed)
            preferenceManager.persistTimeCreditValues()

            val C = preferenceManager.getTimeCreditBalanceSeconds()
            handleZoneTransition(C, isDoze)
        }
    }

    private fun runExhaustionTimer() {
        if (!preferenceManager.isCreditSessionActive()) return
        if (preferenceManager.getTimeCreditBalanceSeconds() > 0L) return
        notifyCreditExhausted()
    }

    /** End session, cancel timer, notify callback for immediate block. (10분 쿨타임 기능 제거) */
    private fun notifyCreditExhausted() {
        if (!preferenceManager.isCreditSessionActive()) return
        dangerZoneGraceNotificationSent = false
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
        preferenceManager.setGoldenTimeActive(false)
        preferenceManager.setLastKnownBalanceAtAlive(0L)
        if (!isGoldenTimeMode) {
            performFinalDeductionOnSessionEnd()
        } else {
            isGoldenTimeMode = false
            preferenceManager.persistTimeCreditValues()
        }
        timeCreditService.endCreditSession()
        Log.w(TAG, "Credit Session 종료(소진): 즉시 차단 알림 package=$packageName")
        packageName?.let { fireCreditExhaustedCallback(it) }
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
                        Log.d(TAG, "Screen ON: 절제 시간 정산 및 타이머 재개")
                        runBlocking(Dispatchers.Default) {
                            calculateAccumulatedAbstention()
                            timeCreditService.settleCredits()
                            if (preferenceManager.isCreditSessionActive()) syncState()
                        }
                        screenOnSettlementDoneCallback?.invoke()
                        if (!preferenceManager.isCreditSessionActive()) startTickJob()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        performFinalDeductionOnScreenOff()
                        preferenceManager.persistTimeCreditValues()
                        isScreenOn = false
                        tickJob?.cancel()
                        tickJob = null
                        stopSessionMonitor()
                        preferenceManager.setLastScreenOffTime(System.currentTimeMillis())
                        preferenceManager.setLastScreenOffElapsedRealtime(SystemClock.elapsedRealtime())
                        if (preferenceManager.isCreditSessionActive()) {
                            runBlocking(Dispatchers.Default) { syncState() }
                            startScreenOffDeductionJobIfNeeded()
                        }
                        Log.d(TAG, "Screen OFF: 최종 차감·persist 후 타이머·세션모니터 중지")
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

    private suspend fun checkBlockedAppAudioFromConfigs(configs: List<AudioPlaybackConfiguration>) {
        try {
            if (AppBlockingService.isPersonaAudioPossiblyPlaying()) return
            val hasActiveAudio = configs.isNotEmpty() && audioManager.isMusicActive
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
     * UsageStatsManager로 "지금 화면에 떠 있는 앱" 직접 확인, 실패 시 lastMiningApp 폴백.
     */
    private fun isSessionAppInForeground(sessionPackage: String): Boolean {
        getCurrentForegroundPackageInternal()?.let { foreground ->
            return foreground == sessionPackage
        }
        val lastApp = preferenceManager.getLastMiningApp() ?: return false
        return lastApp == sessionPackage
    }

    /**
     * 동기: 지정 패키지가 현재 오디오 재생 중인지 조회.
     * MediaSessionManager(API 21+)로 STATE_PLAYING 세션 확인, 불가 시 isMusicActive + lastMiningApp 폴백.
     */
    private fun computeCurrentAudioStatusInternal(packageName: String): Boolean {
        if (AppBlockingService.isPersonaAudioPossiblyPlaying()) return false
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
                        if (stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_BUFFERING) {
                            Log.d(TAG, "[오디오 스냅샷] MediaSessionManager: $packageName state=$stateCode (PLAYING/BUFFERING)")
                            return true
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.d(TAG, "[오디오 스냅샷] MediaSessionManager 권한 없음, 폴백 사용")
            } catch (e: Exception) {
                Log.d(TAG, "[오디오 스냅샷] MediaSessionManager 실패: ${e.message}, 폴백 사용")
            }
        }
        return fallbackCurrentAudioStatus(packageName)
    }

    private fun fallbackCurrentAudioStatus(packageName: String): Boolean {
        val active = audioManager.isMusicActive
        val lastApp = preferenceManager.getLastMiningApp()
        val match = active && lastApp == packageName
        Log.d(TAG, "[오디오 스냅샷] 폴백 진단: isMusicActive=$active, lastApp=$lastApp, packageName=$packageName, match=$match")
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
