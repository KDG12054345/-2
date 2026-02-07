package com.faust.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.faust.data.utils.PreferenceManager
import java.util.concurrent.TimeUnit

/**
 * 엄격모드 상태 관리 및 타이머 로직
 * 
 * 역할: 사용자가 설정한 집중 시간 동안 앱 삭제와 접근성 서비스 해제를 기술적으로 제한하는 기능을 관리합니다.
 * 트리거: MainFragment에서 엄격모드 활성화 버튼 클릭
 * 처리: 엄격모드 활성화, 타이머 설정, 자동 해제 스케줄링
 */
object StrictModeService {
    private const val TAG = "StrictModeService"
    private const val REQUEST_CODE = 1005

    /**
     * 비상구 처리 결과
     */
    data class EmergencyExitResult(
        val success: Boolean,              // 처리 성공 여부 (쿨타임 중이거나 이미 해제된 경우 false)
        val immediateRelease: Boolean,     // 즉시 해제 여부 (remMin <= 5일 때 true)
        val timeReducedMinutes: Int,      // 줄어든 시간 (분)
        val newEndTime: Long,              // 새로운 종료 시각 (밀리초, 즉시 해제 시 0)
        val cooldownMinutes: Int,          // 적용된 쿨타임 (분, 즉시 해제 시 0)
        val message: String                // 사용자에게 표시할 메시지 (실패 시 실패 이유 포함)
    )

    /**
     * 엄격모드를 활성화하고 타이머를 설정합니다.
     * 
     * @param context Context
     * @param durationMinutes 집중 시간 (분)
     */
    fun enableStrictMode(context: Context, durationMinutes: Int) {
        try {
            val preferenceManager = PreferenceManager(context)
            // TODO: 시간 동기화 취약점 보완
            // 현재는 System.currentTimeMillis()를 사용하지만, 사용자가 시스템 시간을 수동으로 변경할 수 있는 취약점이 있습니다.
            // 보완 방안:
            // 1. SystemClock.elapsedRealtime()을 병행 사용하여 상대 시간 추적
            // 2. NetworkTime 라이브러리 도입을 검토하여 서버 시간 기준으로 삼기
            val endTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)

            // 상태 저장
            preferenceManager.setStrictModeActive(true)
            preferenceManager.setStrictModeEndTime(endTime)

            // AlarmManager에 알람 등록
            scheduleStrictModeExpiration(context, endTime)

            Log.d(TAG, "Strict mode enabled for $durationMinutes minutes, expires at $endTime")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable strict mode", e)
        }
    }

    /**
     * 모든 보호 로직을 해제합니다.
     * 
     * @param context Context
     */
    fun disableStrictProtection(context: Context) {
        try {
            val preferenceManager = PreferenceManager(context)

            // 상태 저장
            preferenceManager.setStrictModeActive(false)
            preferenceManager.setStrictModeEndTime(0)

            // AlarmManager 알람 취소
            cancelStrictModeExpiration(context)

            Log.d(TAG, "Strict mode disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable strict protection", e)
        }
    }

    /**
     * 엄격모드 활성 상태를 확인합니다.
     * 
     * @param context Context
     * @return 엄격모드가 활성화되어 있으면 true
     */
    fun isStrictActive(context: Context): Boolean {
        return try {
            val preferenceManager = PreferenceManager(context)
            val isActive = preferenceManager.isStrictModeActive()
            
            // 종료 시간이 지났는지 확인
            if (isActive) {
                val endTime = preferenceManager.getStrictModeEndTime()
                val currentTime = System.currentTimeMillis()
                if (currentTime >= endTime) {
                    // 시간이 지났으면 자동으로 비활성화
                    disableStrictProtection(context)
                    false
                } else {
                    true
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check strict mode active state", e)
            false
        }
    }

    /**
     * 비상구를 처리합니다.
     * 남은 시간에 따라 구간별 차등 보상을 제공합니다.
     * 
     * @param context Context
     * @return 비상구 처리 결과
     */
    fun processEmergencyExit(context: Context): EmergencyExitResult {
        return try {
            val preferenceManager = PreferenceManager(context)
            val currentTime = System.currentTimeMillis()

            // 유효성 검사: 남은 시간 조회
            val remainingTime = getRemainingTime(context)
            if (remainingTime <= 0) {
                return EmergencyExitResult(
                    success = false,
                    immediateRelease = false,
                    timeReducedMinutes = 0,
                    newEndTime = 0,
                    cooldownMinutes = 0,
                    message = "이미 해제된 상태입니다"
                )
            }

            // 쿨타임 체크
            val lastClickTime = preferenceManager.getEmergencyExitLastClickTime()
            val cooldownMinutes = preferenceManager.getEmergencyExitCooldownMinutes()
            
            if (lastClickTime > 0 && cooldownMinutes > 0) {
                val cooldownEndTime = lastClickTime + (cooldownMinutes * 60 * 1000L)
                if (currentTime < cooldownEndTime) {
                    val remainingCooldownMinutes = TimeUnit.MILLISECONDS.toMinutes(cooldownEndTime - currentTime).toInt()
                    return EmergencyExitResult(
                        success = false,
                        immediateRelease = false,
                        timeReducedMinutes = 0,
                        newEndTime = 0,
                        cooldownMinutes = 0,
                        message = "쿨타임 중입니다 (남은 시간: ${remainingCooldownMinutes}분)"
                    )
                }
            }

            // 남은 시간을 분 단위로 계산
            val remMin = TimeUnit.MILLISECONDS.toMinutes(remainingTime)
            val endTime = preferenceManager.getStrictModeEndTime()

            // 구간별 처리
            when {
                remMin <= 5 -> {
                    // 즉시 해제
                    disableStrictProtection(context)
                    EmergencyExitResult(
                        success = true,
                        immediateRelease = true,
                        timeReducedMinutes = 0,
                        newEndTime = 0,
                        cooldownMinutes = 0,
                        message = "엄격모드가 즉시 해제되었습니다"
                    )
                }
                remMin < 10 -> {
                    // 5분 단축, 쿨타임 5분
                    val newEndTime = endTime - (5 * 60 * 1000L)
                    processTimeReduction(context, preferenceManager, newEndTime, currentTime, 5, 5, false)
                }
                remMin < 15 -> {
                    // 5분 단축, 쿨타임 5분
                    val newEndTime = endTime - (5 * 60 * 1000L)
                    processTimeReduction(context, preferenceManager, newEndTime, currentTime, 5, 5, false)
                }
                remMin < 20 -> {
                    // 5분 단축, 쿨타임 5분
                    val newEndTime = endTime - (5 * 60 * 1000L)
                    processTimeReduction(context, preferenceManager, newEndTime, currentTime, 5, 5, false)
                }
                else -> {
                    // 절반 단축, 점진적 쿨타임
                    val halfRemainingMinutes = remMin / 2
                    val newEndTime = currentTime + (halfRemainingMinutes * 60 * 1000L)
                    val progressiveCooldown = minOf(20, 5 + ((remMin - 20) / 2).toInt())
                    processTimeReduction(context, preferenceManager, newEndTime, currentTime, halfRemainingMinutes.toInt(), progressiveCooldown, true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process emergency exit", e)
            EmergencyExitResult(
                success = false,
                immediateRelease = false,
                timeReducedMinutes = 0,
                newEndTime = 0,
                cooldownMinutes = 0,
                message = "비상구 처리 중 오류가 발생했습니다: ${e.message}"
            )
        }
    }

    /**
     * 시간 단축 처리를 수행합니다.
     * 
     * @param context Context
     * @param preferenceManager PreferenceManager
     * @param newEndTime 새로운 종료 시각
     * @param currentTime 현재 시각
     * @param timeReducedMinutes 줄어든 시간 (분)
     * @param cooldownMinutes 쿨타임 (분)
     * @param isHalfReduction 절반 단축 여부
     * @return 처리 결과
     */
    private fun processTimeReduction(
        context: Context,
        preferenceManager: PreferenceManager,
        newEndTime: Long,
        currentTime: Long,
        timeReducedMinutes: Int,
        cooldownMinutes: Int,
        isHalfReduction: Boolean
    ): EmergencyExitResult {
        // 엣지 케이스: newEndTime이 현재 시간보다 과거면 즉시 해제
        if (newEndTime <= currentTime) {
            disableStrictProtection(context)
            return EmergencyExitResult(
                success = true,
                immediateRelease = true,
                timeReducedMinutes = timeReducedMinutes,
                newEndTime = 0,
                cooldownMinutes = 0,
                message = "엄격모드가 즉시 해제되었습니다"
            )
        }

        // 기존 알람 취소
        cancelStrictModeExpiration(context)

        // EndTime 갱신 및 저장
        preferenceManager.setStrictModeEndTime(newEndTime)

        // AlarmManager 재예약
        scheduleStrictModeExpiration(context, newEndTime)

        // 쿨타임 저장
        preferenceManager.setEmergencyExitLastClickTime(currentTime)
        preferenceManager.setEmergencyExitCooldownMinutes(cooldownMinutes)

        // 메시지 생성
        val message = if (isHalfReduction) {
            "종료 시간이 절반으로 단축되었습니다 (쿨타임: ${cooldownMinutes}분)"
        } else {
            "종료 시간이 ${timeReducedMinutes}분 앞당겨졌습니다 (쿨타임: ${cooldownMinutes}분)"
        }

        Log.d(TAG, "Emergency exit processed: time reduced $timeReducedMinutes minutes, cooldown $cooldownMinutes minutes")

        return EmergencyExitResult(
            success = true,
            immediateRelease = false,
            timeReducedMinutes = timeReducedMinutes,
            newEndTime = newEndTime,
            cooldownMinutes = cooldownMinutes,
            message = message
        )
    }

    /**
     * 남은 시간을 조회합니다.
     * 
     * @param context Context
     * @return 남은 시간 (밀리초), 엄격모드가 비활성화되어 있으면 0
     */
    fun getRemainingTime(context: Context): Long {
        return try {
            val preferenceManager = PreferenceManager(context)
            if (!preferenceManager.isStrictModeActive()) {
                return 0L
            }

            val endTime = preferenceManager.getStrictModeEndTime()
            val currentTime = System.currentTimeMillis()
            val remaining = endTime - currentTime

            if (remaining <= 0) {
                // 시간이 지났으면 자동으로 비활성화
                disableStrictProtection(context)
                0L
            } else {
                remaining
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remaining time", e)
            0L
        }
    }

    /**
     * 엄격모드 만료 알람을 스케줄링합니다.
     * 
     * @param context Context
     * @param endTime 만료 시간 (timestamp)
     */
    private fun scheduleStrictModeExpiration(context: Context, endTime: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, StrictModeExpiredReceiver::class.java).apply {
                action = "com.faust.STRICT_MODE_EXPIRED"
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
                            endTime,
                            pendingIntent
                        )
                        Log.d(TAG, "Exact alarm scheduled for strict mode expiration")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException: SCHEDULE_EXACT_ALARM permission not granted", e)
                        // SecurityException 발생 시 비정확 알람으로 폴백
                        try {
                            alarmManager.set(
                                AlarmManager.RTC_WAKEUP,
                                endTime,
                                pendingIntent
                            )
                            Log.w(TAG, "Fallback to inexact alarm succeeded after SecurityException")
                        } catch (fallbackException: Exception) {
                            Log.e(TAG, "Failed to schedule alarm (both exact and inexact)", fallbackException)
                        }
                    }
                } else {
                    // 권한이 없으면 비정확 알람 사용
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        endTime,
                        pendingIntent
                    )
                    Log.w(TAG, "Using inexact alarm for strict mode expiration (exact alarm permission not granted)")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0 (API 23) 이상: setExactAndAllowWhileIdle 사용
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        endTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Exact alarm scheduled for strict mode expiration (API < 31)")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException: SCHEDULE_EXACT_ALARM permission not granted (API < 31)", e)
                    // SecurityException 발생 시 비정확 알람으로 폴백
                    try {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            endTime,
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
                        endTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Exact alarm scheduled for strict mode expiration (API < 23)")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException when scheduling exact alarm (API < 23)", e)
                    // SecurityException 발생 시 비정확 알람으로 폴백
                    try {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            endTime,
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
                val intent = Intent(context, StrictModeExpiredReceiver::class.java).apply {
                    action = "com.faust.STRICT_MODE_EXPIRED"
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    endTime,
                    pendingIntent
                )
                Log.w(TAG, "Final fallback to inexact alarm succeeded")
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Failed to schedule alarm (all methods failed)", fallbackException)
            }
        }
    }

    /**
     * 엄격모드 만료 알람을 취소합니다.
     * 
     * @param context Context
     */
    private fun cancelStrictModeExpiration(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, StrictModeExpiredReceiver::class.java).apply {
                action = "com.faust.STRICT_MODE_EXPIRED"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Strict mode expiration alarm cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel strict mode expiration alarm", e)
        }
    }
}
