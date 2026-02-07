package com.faust.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.data.utils.TimeUtils
import com.faust.models.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * [시스템 진입점: 시간 기반 진입점 / 부팅 진입점]
 * 
 * 역할: AlarmManager에 의해 매주 월요일 00:00에 시스템이 브로드캐스트를 던져 정산 로직을 실행시키는 지점입니다.
 * 또한 기기 재부팅 시 ACTION_BOOT_COMPLETED 이벤트를 수신하여 중단된 서비스와 알람을 재등록하는 지점입니다.
 * 트리거: AlarmManager 트리거 (월요일 00:00) 또는 ACTION_BOOT_COMPLETED 브로드캐스트
 * 처리: 주간 정산 로직 실행 또는 알람 재등록
 * 
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class WeeklyResetReceiver : BroadcastReceiver() {
    companion object {
        private const val GO_ASYNC_TIMEOUT_MS = 8_000L
    }

    /**
     * [시스템 진입점: 시간 기반 진입점 / 부팅 진입점]
     * 
     * 역할: AlarmManager 트리거 또는 부팅 완료 이벤트를 수신하여 정산 로직을 실행합니다.
     * 트리거: "com.faust.WEEKLY_RESET" 액션 또는 ACTION_BOOT_COMPLETED
     * 처리: goAsync() + applicationScope에서 performResetInternal() 호출
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "com.faust.WEEKLY_RESET") {
            val pendingResult = goAsync()
            val app = context.applicationContext as FaustApplication
            app.applicationScope.launch {
                try {
                    withTimeout(GO_ASYNC_TIMEOUT_MS) {
                        WeeklyResetService.performResetInternal(context)
                    }
                } catch (e: TimeoutCancellationException) {
                    android.util.Log.e("WeeklyResetReceiver", "주간 정산 타임아웃 (${GO_ASYNC_TIMEOUT_MS}ms 초과)", e)
                } catch (e: Exception) {
                    android.util.Log.e("WeeklyResetReceiver", "주간 정산 실패", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

object WeeklyResetService {
    private const val TAG = "WeeklyResetService"
    private const val REQUEST_CODE = 1003
    private const val RESET_THRESHOLD = 100
    private const val RESET_COOLDOWN_MS = 60_000L  // 1분 쿨다운 (중복 트리거 방지)

    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: AlarmManager에 주간 정산 알람을 등록합니다. 매주 월요일 00:00에 WeeklyResetReceiver를 트리거합니다.
     * 트리거: MainActivity.onCreate() 또는 performReset() 완료 후
     * 처리: AlarmManager에 다음 월요일 00:00 알람 등록
     */
    fun scheduleWeeklyReset(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WeeklyResetReceiver::class.java).apply {
                action = "com.faust.WEEKLY_RESET"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nextMondayMidnight = TimeUtils.getNextMondayMidnight()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 (API 31) 이상: 정확한 알람 권한 확인
                if (alarmManager.canScheduleExactAlarms()) {
                    try {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            nextMondayMidnight,
                            pendingIntent
                        )
                        Log.d(TAG, "Exact alarm scheduled for weekly reset")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException: SCHEDULE_EXACT_ALARM permission not granted. " +
                                "User needs to grant exact alarm permission in settings.", e)
                        // 권한 설정 화면으로 유도
                        requestExactAlarmPermission(context)
                        // SecurityException 발생 시 비정확 알람으로 폴백
                        try {
                            alarmManager.set(
                                AlarmManager.RTC_WAKEUP,
                                nextMondayMidnight,
                                pendingIntent
                            )
                            Log.w(TAG, "Fallback to inexact alarm succeeded after SecurityException")
                        } catch (fallbackException: Exception) {
                            Log.e(TAG, "Failed to schedule alarm (both exact and inexact)", fallbackException)
                        }
                    }
                } else {
                    // 권한이 없으면 설정 화면으로 유도
                    Log.e(TAG, "SCHEDULE_EXACT_ALARM permission not granted. " +
                            "User needs to grant exact alarm permission in settings.")
                    requestExactAlarmPermission(context)
                    Log.d(TAG, "requestExactAlarmPermission() called successfully")
                    // 폴백: 비정확 알람으로 예약
                    try {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            nextMondayMidnight,
                            pendingIntent
                        )
                        Log.w(TAG, "Fallback to inexact alarm scheduled")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to schedule inexact alarm", e)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0 (API 23) 이상: setExactAndAllowWhileIdle 사용
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextMondayMidnight,
                        pendingIntent
                    )
                    Log.d(TAG, "Exact alarm scheduled for weekly reset (API < 31)")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException: SCHEDULE_EXACT_ALARM permission not granted (API < 31). " +
                            "User needs to grant exact alarm permission in settings.", e)
                    // SecurityException 발생 시 비정확 알람으로 폴백
                    try {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            nextMondayMidnight,
                            pendingIntent
                        )
                        Log.w(TAG, "Fallback to inexact alarm succeeded after SecurityException")
                    } catch (fallbackException: Exception) {
                        Log.e(TAG, "Failed to schedule alarm (both exact and inexact)", fallbackException)
                    }
                }
            } else {
                // Android 6.0 미만: setExact 사용 (deprecated)
                try {
                    @Suppress("DEPRECATION")
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        nextMondayMidnight,
                        pendingIntent
                    )
                    Log.d(TAG, "Exact alarm scheduled for weekly reset (API < 23)")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException when scheduling exact alarm (API < 23)", e)
                    // SecurityException 발생 시 비정확 알람으로 폴백
                    try {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            nextMondayMidnight,
                            pendingIntent
                        )
                        Log.w(TAG, "Fallback to inexact alarm succeeded after SecurityException")
                    } catch (fallbackException: Exception) {
                        Log.e(TAG, "Failed to schedule alarm (both exact and inexact)", fallbackException)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception when scheduling alarm", e)
            // 최종 폴백: 비정확 알람으로 예약
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, WeeklyResetReceiver::class.java).apply {
                    action = "com.faust.WEEKLY_RESET"
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val nextMondayMidnight = TimeUtils.getNextMondayMidnight()
                
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    nextMondayMidnight,
                    pendingIntent
                )
                Log.w(TAG, "Final fallback to inexact alarm succeeded")
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Failed to schedule alarm (all methods failed)", fallbackException)
            }
        }
    }

    /**
     * 정확한 알람 권한 설정 화면으로 유도합니다.
     * Android 12 (API 31) 이상에서만 호출됩니다.
     * Activity 컨텍스트에서만 호출되어야 합니다.
     */
    private fun requestExactAlarmPermission(context: Context) {
        Log.d(TAG, "requestExactAlarmPermission() called")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Activity 컨텍스트인지 확인 (백그라운드에서 호출되는 경우 방지)
            val activityContext = when {
                context is android.app.Activity -> {
                    Log.d(TAG, "Context is Activity, proceeding to open settings")
                    context
                }
                context is androidx.fragment.app.FragmentActivity -> {
                    Log.d(TAG, "Context is FragmentActivity, proceeding to open settings")
                    context
                }
                else -> {
                    Log.w(TAG, "Cannot open settings: context is not an Activity. " +
                            "Exact alarm permission should be requested from Activity context.")
                    return
                }
            }
            
            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${activityContext.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activityContext.startActivity(intent)
                Log.d(TAG, "Opened exact alarm permission settings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open exact alarm permission settings", e)
                // 대체 방법: 일반 앱 설정 화면으로 이동
                try {
                    val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activityContext.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    activityContext.startActivity(fallbackIntent)
                    Log.d(TAG, "Opened app settings as fallback")
                } catch (fallbackException: Exception) {
                    Log.e(TAG, "Failed to open app settings", fallbackException)
                }
            }
        }
    }

    /**
     * [Receiver 전용] suspend 버전.
     * DB 트랜잭션은 취소 가능 (타임아웃 시 Room이 자동 롤백).
     * 후처리(시간 기록, 알람 재스케줄링)만 NonCancellable.
     */
    suspend fun performResetInternal(context: Context) {
        val preferenceManager = PreferenceManager(context)
        val lastResetTime = preferenceManager.getLastResetTime()
        val elapsed = System.currentTimeMillis() - lastResetTime
        if (elapsed < RESET_COOLDOWN_MS) {
            Log.w(TAG, "정산 건너뜀: 마지막 정산으로부터 ${elapsed}ms 이내 (쿨다운 ${RESET_COOLDOWN_MS}ms)")
            return
        }
        try {
            withContext(Dispatchers.IO) {
                val database = (context.applicationContext as FaustApplication).database
                database.withTransaction {
                    try {
                        val currentPoints = (database.pointTransactionDao().getTotalPoints() ?: 0).coerceAtLeast(0)
                        if (currentPoints > RESET_THRESHOLD) {
                            val pointsToRemove = currentPoints - RESET_THRESHOLD
                            database.pointTransactionDao().insertTransaction(
                                com.faust.models.PointTransaction(
                                    amount = -pointsToRemove,
                                    type = TransactionType.RESET,
                                    reason = "주간 정산: 100 WP 제외 몰수"
                                )
                            )
                            preferenceManager.setCurrentPoints(RESET_THRESHOLD)
                        } else {
                            if (currentPoints > 0) {
                                database.pointTransactionDao().insertTransaction(
                                    com.faust.models.PointTransaction(
                                        amount = -currentPoints,
                                        type = TransactionType.RESET,
                                        reason = "주간 정산: 전액 몰수"
                                    )
                                )
                            }
                            preferenceManager.setCurrentPoints(0)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error performing reset in transaction", e)
                        throw e
                    }
                }
                preferenceManager.setLastResetTime(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "주간 정산 실패 (DB 트랜잭션 자동 롤백됨)", e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val appContext = context.applicationContext
                    if (appContext != null) {
                        scheduleWeeklyReset(appContext)
                        Log.d(TAG, "다음 주간 정산 스케줄링 완료 (NonCancellable)")
                    } else {
                        Log.e(TAG, "Cannot schedule next reset: applicationContext is null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "다음 주간 정산 스케줄링 실패", e)
                }
            }
        }
    }

    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: 주간 정산 로직을 실행합니다. DB 트랜잭션으로 포인트 조정과 거래 내역 저장을 원자적으로 처리합니다.
     * 트리거: AlarmManager가 매주 월요일 00:00에 WeeklyResetReceiver를 통해 호출 (또는 직접 호출 시 하위 호환)
     * 처리: performResetInternal() 호출
     */
    fun performReset(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            performResetInternal(context)
        }
    }

    /**
     * 부팅 완료 시 주간 정산을 다시 스케줄링합니다.
     */
    fun rescheduleOnBoot(context: Context) {
        val appContext = context.applicationContext
        if (appContext != null) {
            scheduleWeeklyReset(appContext)
        } else {
            Log.e(TAG, "Cannot reschedule on boot: applicationContext is null")
        }
    }
}
