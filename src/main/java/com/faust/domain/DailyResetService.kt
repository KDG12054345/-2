package com.faust.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.faust.FaustApplication
import com.faust.data.utils.PreferenceManager
import com.faust.data.utils.TimeUtils
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
 * 역할: AlarmManager에 의해 매일 사용자 지정 시간에 시스템이 브로드캐스트를 던져 일일 초기화 로직을 실행시키는 지점입니다.
 * 또한 기기 재부팅 시 ACTION_BOOT_COMPLETED 이벤트를 수신하여 중단된 알람을 재등록하는 지점입니다.
 * 트리거: AlarmManager 트리거 (사용자 지정 시간) 또는 ACTION_BOOT_COMPLETED 브로드캐스트
 * 처리: 일일 초기화 로직 실행 또는 알람 재등록
 * 
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class DailyResetReceiver : BroadcastReceiver() {
    companion object {
        /**
         * goAsync() 타임아웃.
         * Android 시스템은 goAsync() 후 약 10초 이내에 finish()를 기대.
         * 8초로 설정하여 2초 안전 마진 확보.
         * 실제 정산 작업은 < 1초이므로 정상 상황에서는 타임아웃 미도달.
         */
        private const val GO_ASYNC_TIMEOUT_MS = 8_000L
    }

    /**
     * [시스템 진입점: 시간 기반 진입점 / 부팅 진입점]
     * 
     * 역할: AlarmManager 트리거 또는 부팅 완료 이벤트를 수신하여 일일 초기화 로직을 실행합니다.
     * 트리거: "com.faust.DAILY_RESET" 액션 또는 ACTION_BOOT_COMPLETED
     * 처리: goAsync() + applicationScope에서 performResetInternal() 호출
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "com.faust.DAILY_RESET") {
            val pendingResult = goAsync()
            val app = context.applicationContext as FaustApplication
            app.applicationScope.launch {
                try {
                    withTimeout(GO_ASYNC_TIMEOUT_MS) {
                        DailyResetService.performResetInternal(context)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e("DailyResetReceiver", "일일 초기화 타임아웃 (${GO_ASYNC_TIMEOUT_MS}ms 초과)", e)
                } catch (e: Exception) {
                    Log.e("DailyResetReceiver", "일일 초기화 실패", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

object DailyResetService {
    private const val TAG = "DailyResetService"
    private const val REQUEST_CODE = 1004
    private const val RESET_COOLDOWN_MS = 60_000L  // 1분 쿨다운 (중복 트리거 방지)

    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: AlarmManager에 일일 초기화 알람을 등록합니다. 매일 사용자 지정 시간에 DailyResetReceiver를 트리거합니다.
     * 트리거: MainActivity.onCreate() 또는 performReset() 완료 후 또는 사용자 지정 시간 변경 시
     * 처리: AlarmManager에 다음 사용자 지정 시간 알람 등록
     */
    fun scheduleDailyReset(context: Context): Long {
        try {
            val preferenceManager = PreferenceManager(context)
            val customTime = preferenceManager.getCustomDailyResetTime()
            val nextResetTime = getNextResetTime(customTime)

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DailyResetReceiver::class.java).apply {
                action = "com.faust.DAILY_RESET"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 (API 31) 이상: 정확한 알람 권한 확인
                if (alarmManager.canScheduleExactAlarms()) {
                    try {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            nextResetTime,
                            pendingIntent
                        )
                        Log.d(TAG, "Exact alarm scheduled for daily reset at $customTime")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException: SCHEDULE_EXACT_ALARM permission not granted", e)
                        // 비정확 알람으로 폴백
                        try {
                            alarmManager.set(
                                AlarmManager.RTC_WAKEUP,
                                nextResetTime,
                                pendingIntent
                            )
                            Log.w(TAG, "Fallback to inexact alarm for daily reset")
                        } catch (fallbackException: Exception) {
                            Log.e(TAG, "Failed to schedule daily reset alarm", fallbackException)
                        }
                    }
                } else {
                    // 권한이 없으면 비정확 알람 사용
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        nextResetTime,
                        pendingIntent
                    )
                    Log.w(TAG, "Using inexact alarm for daily reset (exact alarm permission not granted)")
                }
            } else {
                // Android 11 이하: setExact 사용
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextResetTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        nextResetTime,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Exact alarm scheduled for daily reset at $customTime (API < 31)")
            }

            return nextResetTime
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule daily reset", e)
            return 0L
        }
    }

    /**
     * 사용자 지정 시간 기준으로 다음 리셋 시간을 계산합니다.
     * 
     * @param customTime "HH:mm" 형식의 사용자 지정 시간 (예: "02:00")
     * @return 다음 리셋 시간의 timestamp (밀리초)
     */
    fun getNextResetTime(customTime: String): Long {
        return TimeUtils.getNextResetTime(customTime)
    }

    /**
     * [Receiver 전용] suspend 버전. goAsync() 컨텍스트에서 호출.
     * 정산 로직은 취소 가능 (타임아웃 시 중단). 후처리(알람 재스케줄링)만 NonCancellable.
     */
    suspend fun performResetInternal(context: Context) {
        val preferenceManager = PreferenceManager(context)
        val lastResetTime = preferenceManager.getLastDailyResetTime()
        val elapsed = System.currentTimeMillis() - lastResetTime
        if (elapsed < RESET_COOLDOWN_MS) {
            Log.w(TAG, "정산 건너뜀: 마지막 일일 정산으로부터 ${elapsed}ms 이내 (쿨다운 ${RESET_COOLDOWN_MS}ms)")
            return
        }
        try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "일일 초기화 실행 (TimeCredit 확장 준비 완료)")
                // 성공 시에만 쿨다운 타임스탬프 갱신
                preferenceManager.setLastDailyResetTime(System.currentTimeMillis())
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val appContext = context.applicationContext
                    if (appContext != null) {
                        scheduleDailyReset(appContext)
                        Log.d(TAG, "다음 일일 초기화 스케줄링 완료 (NonCancellable)")
                    } else {
                        Log.e(TAG, "Cannot schedule next daily reset: applicationContext is null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "다음 일일 초기화 스케줄링 실패", e)
                }
            }
        }
    }

    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: 일일 초기화 로직을 실행합니다.
     * 트리거: AlarmManager 트리거 (사용자 지정 시간) — 직접 호출 시 (하위 호환)
     * 처리: performResetInternal() 호출
     */
    fun performReset(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            performResetInternal(context)
        }
    }

    /**
     * 부팅 완료 시 일일 초기화를 다시 스케줄링합니다.
     */
    fun rescheduleOnBoot(context: Context) {
        scheduleDailyReset(context)
    }
}
