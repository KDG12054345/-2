package com.faust.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Foreground Service 시작 (앱이 종료되어도 죽지 않음)
        startForeground(NOTIFICATION_ID, createNotification())
        preferenceManager.setServiceRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        // 서비스 시작 시 타이머를 현재 시간으로 리셋 (과거 기록으로 인한 오적립 방지)
        preferenceManager.setLastMiningTime(System.currentTimeMillis())
        Log.d(TAG, "Mining Service Started - Timer Reset")

        startMining()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        miningJob?.cancel()
        serviceScope.cancel()
        cancelRetryAlarm()
        preferenceManager.setServiceRunning(false)
        Log.d(TAG, "Mining Service Stopped")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startMining() {
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

        // 2. 차단된 앱인지 확인
        val isBlocked = database.appBlockDao().getBlockedApp(currentApp) != null
        if (isBlocked) {
            Log.d(TAG, "Mining paused: $currentApp is blocked ⛔")
            // 차단 앱 사용 시 타이머 리셋 (채굴 중단)
            preferenceManager.setLastMiningTime(System.currentTimeMillis())
            preferenceManager.setLastMiningApp(currentApp)
            return
        }

        // 3. 타이머 로직
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
            // 테스트용: 1분당 1포인트 고정
            val pointsToAdd = 1

            addMiningPoints(pointsToAdd)

            // 5. 시간 갱신 (Dripping: 소진된 1분만 더해줌)
            val newTime = lastMiningTime + (1000 * 60)
            preferenceManager.setLastMiningTime(newTime)

            Log.d(TAG, "💰 Point Added! Next check starts from: $newTime")
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
