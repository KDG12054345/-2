package com.faust.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.R
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.models.TransactionType
import com.faust.models.UserTier
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

    companion object {
        private const val TAG = "PointMiningService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "point_mining_channel"
        private const val MINING_INTERVAL_MS = 60_000L // 1분마다 체크
        private const val POINTS_PER_10_MINUTES = 1 // 10분당 1 WP
        private const val FREE_TIER_EFFICIENCY = 0.5 // Free 티어는 0.5x 효율

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
        startForeground(NOTIFICATION_ID, createNotification())
        preferenceManager.setServiceRunning(true)
    }

    /**
     * [시스템 진입점: 백그라운드 유지 진입점]
     * 
     * 역할: Foreground Service 시작 시 포인트 채굴 루프를 시작합니다.
     * 트리거: MainActivity.startServices() 또는 PointMiningService.startService() 호출
     * 처리: Foreground Service 시작, 포인트 채굴 루프 시작
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startMining()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        miningJob?.cancel()
        serviceScope.cancel()
        preferenceManager.setServiceRunning(false)
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
                    // 에러 발생 시 계속 진행
                }
            }
        }
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - processMining]
     * 
     * 역할: PointMiningService에서 1분마다 실행되며, 현재 사용 중인 앱이 차단 목록에 없을 경우 시간에 비례해 포인트를 적립합니다.
     * 트리거: 1분마다 자동 실행 (startMining() 루프에서 호출)
     * 처리: 포그라운드 앱 확인, 차단 목록 확인, 같은 앱 사용 시간 계산, 10분당 1 WP 기준으로 포인트 계산 및 적립
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private suspend fun processMining() {
        val currentApp = getCurrentForegroundApp()
        if (currentApp == null) {
            // 포그라운드 앱이 없으면 채굴 중지
            return
        }

        // 차단된 앱인지 확인
        val isBlocked = database.appBlockDao().getBlockedApp(currentApp) != null
        if (isBlocked) {
            // 차단된 앱 사용 중이면 채굴 중지
            preferenceManager.setLastMiningApp(null)
            return
        }

        val lastMiningTime = preferenceManager.getLastMiningTime()
        val lastMiningApp = preferenceManager.getLastMiningApp()
        val currentTime = System.currentTimeMillis()

        if (lastMiningTime == 0L || lastMiningApp != currentApp) {
            // 새로운 앱이거나 첫 채굴 시작
            preferenceManager.setLastMiningTime(currentTime)
            preferenceManager.setLastMiningApp(currentApp)
            return
        }

        // 같은 앱 사용 중 - 채굴 시간 계산
        val elapsedMinutes = (currentTime - lastMiningTime) / (1000 * 60)
        
        // 10분 단위로 포인트 계산 (Free 티어는 0.5x 효율)
        val pointsToAdd = calculatePoints(elapsedMinutes)
        
        if (pointsToAdd > 0) {
            addMiningPoints(pointsToAdd)
            preferenceManager.setLastMiningTime(currentTime)
        }
    }

    private fun calculatePoints(elapsedMinutes: Long): Int {
        val userTier = preferenceManager.getUserTier()
        val efficiency = when (userTier) {
            UserTier.FREE -> FREE_TIER_EFFICIENCY
            UserTier.STANDARD -> 1.0
            UserTier.FAUST_PRO -> 1.0 // MVP에서는 1.0으로 고정
        }

        // 10분당 1 WP 기준으로 계산
        val basePoints = (elapsedMinutes / 10).toInt()
        return (basePoints * efficiency).toInt()
    }

    /**
     * 포인트를 채굴합니다. DB 트랜잭션으로 포인트 적립과 거래 내역 저장을 원자적으로 처리합니다.
     */
    private suspend fun addMiningPoints(points: Int) {
        if (points <= 0) return

        try {
            database.withTransaction {
                try {
                    // 거래 내역 저장 (트랜잭션으로 원자적 처리)
                    database.pointTransactionDao().insertTransaction(
                        com.faust.models.PointTransaction(
                            amount = points,
                            type = TransactionType.MINING,
                            reason = "앱 사용 시간 채굴"
                        )
                    )
                    
                    // PreferenceManager 동기화 (호환성 유지)
                    val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0
                    preferenceManager.setCurrentPoints(currentPoints.coerceAtLeast(0))
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding mining points in transaction: points=$points", e)
                    throw e // 트랜잭션 롤백을 위해 예외 재발생
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add mining points: points=$points", e)
            // 트랜잭션이 실패하면 자동으로 롤백됨
        }
    }

    private fun getCurrentForegroundApp(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val time = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                time - 1000,
                time
            )
            
            if (stats.isNullOrEmpty()) {
                null
            } else {
                stats.maxByOrNull { it.lastTimeUsed }?.packageName
            }
        } catch (e: Exception) {
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
            .setContentText("포인트 채굴 중...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
