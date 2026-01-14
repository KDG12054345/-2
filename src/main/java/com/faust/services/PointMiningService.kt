package com.faust.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
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
 * 처리: 1분마다 포그라운드 앱 확인, 차단되지 않은 앱 사용 시간 추적, 포인트 자동 적립
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
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var consecutiveEmptyStatsCount = 0
    private var screenEventReceiver: BroadcastReceiver? = null

    companion object {
        private const val MAX_CONSECUTIVE_EMPTY_STATS = 3 // 3회 연속 실패 시 재시도 예약
        private const val TAG = "PointMiningService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "point_mining_channel"
        private const val RETRY_ALARM_REQUEST_CODE = 1004
        private const val RETRY_DELAY_MS = 10 * 60 * 1000L // 10분

        // 테스트용 설정: 10초마다 체크, 1분당 1포인트
        private const val MINING_INTERVAL_MS = 10_000L

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
        createNotificationChannel()
        // Foreground Service 시작 (앱이 종료되어도 죽지 않음)
        startForeground(NOTIFICATION_ID, createNotification())
        preferenceManager.setServiceRunning(true)
        
        // 화면 이벤트 리시버 등록
        registerScreenEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        // 서비스 시작 시 타이머를 현재 시간으로 리셋 (과거 기록으로 인한 오적립 방지)
        preferenceManager.setLastMiningTime(System.currentTimeMillis())
        // 화면이 켜져있는 상태로 시작하므로 lastScreenOnTime 설정
        preferenceManager.setLastScreenOnTime(System.currentTimeMillis())
        Log.d(TAG, "Mining Service Started - Timer Reset")

        startMiningJob()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        miningJob?.cancel()
        serviceScope.cancel()
        cancelRetryAlarm()
        unregisterScreenEventReceiver()
        preferenceManager.setServiceRunning(false)
        Log.d(TAG, "Mining Service Stopped")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * 실시간 10초 주기 타이머를 시작합니다.
     * 화면이 켜져있을 때만 실행되며, 화면이 꺼지면 중지됩니다.
     */
    private fun startMiningJob() {
        miningJob?.cancel()
        miningJob = serviceScope.launch {
            while (isActive) {
                try {
                    processMining()
                    delay(MINING_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in mining loop", e)
                }
            }
        }
        Log.d(TAG, "Mining Job Started")
    }

    private suspend fun processMining() {
        // 1. 현재 앱 감지 (queryEvents 사용)
        val currentApp = getCurrentForegroundApp()
        if (currentApp == null) {
            // 감지 실패 시 이번 턴은 스킵
            // UsageStats가 비어있을 때 방어 로직 처리
            handleEmptyUsageStats()
            return
        }

        // 정상적으로 앱을 감지했으면 연속 실패 카운터 리셋
        consecutiveEmptyStatsCount = 0
        cancelRetryAlarm()

        // 2. 차단된 앱인지 확인 (유죄 협상/위반 감지)
        val isBlocked = database.appBlockDao().getBlockedApp(currentApp) != null
        
        if (isBlocked) {
            // 차단 앱 감지: 포인트 적립이 일시 중단됩니다.
            Log.d(TAG, "차단 앱 감지: $currentApp. 포인트 적립이 일시 중단됩니다.")
            
            // 적립 타이머만 현재 시간으로 갱신하여 점수가 쌓이지 않게 차단합니다.
            preferenceManager.setLastMiningTime(System.currentTimeMillis())
            preferenceManager.setLastMiningApp(currentApp)
            return
        }

        // 3. 정상 상태 (디톡스 중): 시간 경과에 따라 포인트 증정
        var lastMiningTime = preferenceManager.getLastMiningTime()
        if (lastMiningTime == 0L) {
            lastMiningTime = System.currentTimeMillis()
            preferenceManager.setLastMiningTime(lastMiningTime)
        }

        val currentTime = System.currentTimeMillis()
        val elapsedMinutes = (currentTime - lastMiningTime) / (1000 * 60)

        Log.d(TAG, "Mining... App: $currentApp, Elapsed: $elapsedMinutes min")

        // 4. 포인트 적립 (1분 이상 경과 시)
        if (elapsedMinutes >= 1) {
            // 1분당 1포인트 자동 적립
            addMiningPoints(1)
            
            // 5. 시간 갱신 (Dripping: 소진된 1분만 더해줌)
            val newTime = lastMiningTime + (1000 * 60)
            preferenceManager.setLastMiningTime(newTime)

            Log.d(TAG, "디톡스 유지 중: 1포인트 적립 완료 💎")
        }

        // 앱이 바뀌어도 차단 앱만 아니면 계속 채굴 유지
        preferenceManager.setLastMiningApp(currentApp)
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
     * 사용자가 '강행'을 선택했을 때 단 한 번 벌금을 부과합니다.
     * @param penaltyAmount 벌금 액수 (예: 10)
     */
    suspend fun applyOneTimePenalty(penaltyAmount: Int) {
        Log.w(TAG, "사용자 강행 선택: 벌금 ${penaltyAmount}WP 부과")
        subtractPoints(penaltyAmount) // 기존에 정의된 차감 함수 활용
    }

    /**
     * UsageStats가 비어있을 때의 방어 로직을 처리합니다.
     * Doze mode나 배터리 최적화로 인해 UsageStats가 비어있을 수 있습니다.
     */
    private fun handleEmptyUsageStats() {
        consecutiveEmptyStatsCount++
        Log.w(TAG, "Usage stats empty - Doze mode suspected. Consecutive failures: $consecutiveEmptyStatsCount")

        // 연속 실패 횟수가 임계값을 넘으면 AlarmManager로 재시도 예약
        if (consecutiveEmptyStatsCount >= MAX_CONSECUTIVE_EMPTY_STATS) {
            Log.w(TAG, "Too many consecutive failures. Scheduling retry in ${RETRY_DELAY_MS / 1000 / 60} minutes...")
            scheduleRetryAlarm()
            consecutiveEmptyStatsCount = 0 // 리셋하여 중복 예약 방지
        }
    }

    /**
     * AlarmManager를 이용해 일정 시간 후 서비스를 재시작하도록 예약합니다.
     */
    private fun scheduleRetryAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, PointMiningService::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                RETRY_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = System.currentTimeMillis() + RETRY_DELAY_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0 이상: setExactAndAllowWhileIdle 사용 (Doze mode에서도 작동)
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                @Suppress("DEPRECATION")
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

            Log.d(TAG, "Retry alarm scheduled for ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerTime))}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when scheduling retry alarm. SCHEDULE_EXACT_ALARM permission may be missing.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling retry alarm", e)
        }
    }

    /**
     * 예약된 재시도 알람을 취소합니다.
     */
    private fun cancelRetryAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, PointMiningService::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                RETRY_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )

            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d(TAG, "Retry alarm cancelled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling retry alarm", e)
        }
    }

    // [핵심 수정] queryEvents를 사용하여 실시간 앱 감지 성능 향상
    private fun getCurrentForegroundApp(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            // 5분 전부터 탐색하여 감지 확률 높임
            val startTime = endTime - (1000 * 60 * 5)

            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            var lastPackage: String? = null
            var lastTime = 0L
            var hasEvents = false

            while (events.hasNextEvent()) {
                hasEvents = true
                events.getNextEvent(event)
                // 앱이 포그라운드로 오거나(MOVE_TO_FOREGROUND) 재개될 때(ACTIVITY_RESUMED)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    
                    if (event.timeStamp > lastTime) {
                        lastTime = event.timeStamp
                        lastPackage = event.packageName
                    }
                }
            }

            if (lastPackage != null) {
                Log.d(TAG, "Detected App (Event): $lastPackage")
            } else if (!hasEvents) {
                // 이벤트 자체가 없는 경우 (UsageStats가 완전히 비어있음)
                Log.w(TAG, "Usage stats completely empty - Doze mode or battery optimization may be active.")
            } else {
                Log.w(TAG, "Usage stats empty or no recent foreground event.")
            }
            lastPackage
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting foreground app", e)
            null
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
                        Log.d(TAG, "Screen ON: 정산 시작 및 타이머 재개")
                        // 1. 화면이 꺼져있던 동안의 포인트 일괄 계산 로직 실행
                        //    (calculateAccumulatedPoints 내부에서 시간 리셋 처리)
                        serviceScope.launch {
                            calculateAccumulatedPoints()
                        }
                        // 2. 10초 주기 타이머 다시 시작
                        startMiningJob()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen OFF: 타이머 중지 및 절전 모드")
                        // 타이머 중지 (Coroutine Job cancel)
                        miningJob?.cancel()
                        miningJob = null
                        // 화면이 꺼진 시간 저장
                        preferenceManager.setLastScreenOffTime(System.currentTimeMillis())
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
     * 화면이 꺼져있던 동안의 포인트를 일괄 계산합니다.
     * 화면이 꺼져 있는 동안은 차단 앱을 쓸 수 없으므로(대부분의 경우),
     * "폰을 꺼둔 시간 = 100% 성공 시간"으로 간주하여 한꺼번에 점수를 줍니다.
     * 
     * 단순화된 로직: 화면이 꺼진 시간부터 화면이 켜진 시간까지의 시간만 계산하여 포인트 지급
     */
    private suspend fun calculateAccumulatedPoints() {
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
