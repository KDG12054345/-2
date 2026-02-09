package com.faust.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.faust.data.utils.PreferenceManager
import com.faust.services.TimeCreditBackgroundService
import com.faust.utils.AppLog
import kotlin.math.min

/**
 * TimeCredit 시스템의 핵심 도메인 서비스입니다.
 *
 * CBT 기반 수반성 관리: 절제 시간에 비례한 보상 크레딧을 정산·사용·관리합니다.
 * 배터리 효율을 위해 백그라운드 실시간 계산을 지양하고,
 * 앱 진입 시점에 일괄 정산(Batch Processing)하는 '깜짝 선물' 방식을 채택합니다.
 *
 * @param context Application 또는 Activity context
 */
class TimeCreditService(private val context: Context) {

    private val preferenceManager = PreferenceManager(context)

    companion object {
        private const val TAG = "TimeCreditService"
        private const val REQUEST_CODE_5MIN_BEFORE = 5001
        private const val REQUEST_CODE_1MIN_BEFORE = 5002
        private const val REQUEST_CODE_PRECISION_TRANSITION = 5003
        const val ACTION_5MIN_BEFORE = "com.faust.TIME_CREDIT_5MIN_BEFORE"
        const val ACTION_1MIN_BEFORE = "com.faust.TIME_CREDIT_1MIN_BEFORE"
        /** 적응형 감시: 안전 구간에서 (C-60)초 후 정밀 감시 전환. 휴면 해제 시에도 사용. */
        const val ACTION_PRECISION_TRANSITION = "com.faust.TIME_CREDIT_PRECISION_TRANSITION"

        /** 세션 시작 시점의 단조 시간(런타임 전용). 재부팅 시 초기화되며, UI 카운트다운 정밀도용. 중간 정산 시 TimeCreditBackgroundService에서 '지금'으로 갱신함. */
        @Volatile
        var sessionStartElapsedRealtime: Long = 0L
    }

    // ══════════════════════════════════════════════════════════════
    // 정산 결과 / 사용 결과 모델 (초 단위)
    // ══════════════════════════════════════════════════════════════

    data class SettlementResult(
        val earnedSeconds: Long,
        val newBalanceSeconds: Long
    )

    sealed class UseResult {
        data class Success(val remainingBalanceSeconds: Long) : UseResult()
        data class Failure(val reason: String) : UseResult()
    }

    // ══════════════════════════════════════════════════════════════
    // 핵심 메서드
    // ══════════════════════════════════════════════════════════════

    /**
     * 앱 진입 시점(MainActivity.onResume)에 호출하여 크레딧을 일괄 정산합니다.
     *
     * Remainder retention: 나머지(ratio로 나누어떨어지지 않는 초)는 다음 정산까지 유지.
     * 예: 59초 절제 / ratio 4 → 14초 보상, 3초 나머지 보관 → 이후 1초 추가 시 4초가 되어 1초 추가 보상.
     *
     * 수식: earnedSeconds = totalSeconds / ratio, remainderSeconds = totalSeconds % ratio
     */
    fun settleCredits(): SettlementResult {
        try {
            if (preferenceManager.isCreditSessionActive()) {
                Log.d(TAG, "${AppLog.CREDIT} session active → settleCredits skipped, balance unchanged")
                return SettlementResult(0L, preferenceManager.getTimeCreditBalanceSeconds())
            }
            val totalSeconds = preferenceManager.getAccumulatedAbstentionSeconds()

            if (totalSeconds <= 0L) {
                Log.d(TAG, "${AppLog.CREDIT} no accumulated abstention → settleCredits skipped, balance unchanged")
                return SettlementResult(0L, preferenceManager.getTimeCreditBalanceSeconds())
            }

            val userRatio = preferenceManager.getTimeCreditUserType().ratio
            val earnedSeconds = totalSeconds / userRatio
            val remainderSeconds = totalSeconds % userRatio

            if (earnedSeconds <= 0L) {
                Log.d(TAG, "${AppLog.CREDIT} abstention ${totalSeconds}s, ratio $userRatio → credit 0, remainder ${remainderSeconds}s kept")
                preferenceManager.setAccumulatedAbstentionSeconds(remainderSeconds)
                return SettlementResult(0L, preferenceManager.getTimeCreditBalanceSeconds())
            }

            val currentBalanceSeconds = preferenceManager.getTimeCreditBalanceSeconds()
            val maxCapSeconds = preferenceManager.getTimeCreditMaxCap() * 60L
            val newBalanceSeconds = min(currentBalanceSeconds + earnedSeconds, maxCapSeconds)
            preferenceManager.setTimeCreditBalanceSeconds(newBalanceSeconds)
            preferenceManager.setAccumulatedAbstentionSeconds(remainderSeconds)
            preferenceManager.setTimeCreditLastSyncTime(SystemClock.elapsedRealtime())

            Log.d(TAG, "${AppLog.CREDIT} abstention ${totalSeconds}s → credit +${earnedSeconds}s, remainder ${remainderSeconds}s (balance: ${newBalanceSeconds}s)")
            return SettlementResult(earnedSeconds, newBalanceSeconds)
        } catch (e: Exception) {
            Log.e(TAG, "${AppLog.CREDIT} settleCredits failed → exception", e)
            return SettlementResult(0L, preferenceManager.getTimeCreditBalanceSeconds())
        }
    }

    /**
     * Credit Session을 시작합니다 (C-2 해결).
     * 차단 앱 접근 허용 시 세션을 활성화하고,
     * 실제 크레딧 차감은 TimeCreditBackgroundService 틱 루프에서 초 단위로 수행됩니다.
     *
     * @param packageName 허용할 차단 앱 패키지명
     */
    fun startCreditSession(packageName: String): UseResult {
        try {
            // 재진입 가드: 이미 활성 세션이면 재시작하지 않음 (sessionStartElapsedRealtime 리셋 방지)
            // 차단 앱 간 전환 시 세션 패키지를 현재 포그라운드 앱으로 갱신 (종료 로그/통계가 마지막 사용 앱 기준이 되도록)
            if (preferenceManager.isCreditSessionActive()) {
                val currentBalance = preferenceManager.getTimeCreditBalanceSeconds()
                val oldPackage = preferenceManager.getCreditSessionPackage()
                if (oldPackage != null && oldPackage != packageName) {
                    preferenceManager.setCreditSessionPackage(packageName)
                    preferenceManager.setLastMiningApp(packageName) // Credit 세션 앱을 마지막 채굴 앱으로 동기화 (화면 OFF 오디오 감지용)
                    Log.d(TAG, "${AppLog.CREDIT} package switch $oldPackage → $packageName (session already active)")
                } else if (oldPackage == packageName) {
                    preferenceManager.setLastMiningApp(packageName) // 재진입 시에도 마지막 채굴 앱 동기화
                }
                TimeCreditBackgroundService.setLastKnownForegroundPackage(packageName) // 휴면 해제
                Log.d(TAG, "${AppLog.CREDIT} session already active → startCreditSession skipped ($packageName, balance: ${currentBalance}s)")
                return UseResult.Success(currentBalance)
            }

            val currentBalanceSeconds = preferenceManager.getTimeCreditBalanceSeconds()
            if (currentBalanceSeconds <= 0L) {
                return UseResult.Failure("크레딧이 부족합니다")
            }

            val startTime = System.currentTimeMillis()
            val startElapsed = SystemClock.elapsedRealtime()
            preferenceManager.setCreditSessionActive(true)
            TimeCreditBackgroundService.setLastKnownForegroundPackage(packageName) // 신규 세션: 포그라운드 = 세션 앱
            preferenceManager.setCreditSessionStartTime(startTime)
            preferenceManager.setCreditSessionStartElapsedRealtime(startElapsed)
            preferenceManager.setTimeCreditLastSyncTime(startElapsed)
            preferenceManager.setCreditAtSessionStartSeconds(currentBalanceSeconds)
            preferenceManager.setLastKnownAliveSessionTime(0L)
            sessionStartElapsedRealtime = startElapsed
            preferenceManager.setCreditSessionPackage(packageName)
            preferenceManager.setLastMiningApp(packageName) // Credit 세션 앱을 마지막 채굴 앱으로 동기화 (화면 OFF 오디오 감지용)

            // Invalidate pending abstinence: no reward for "abstaining" if user starts usage before settlement.
            val wasPending = preferenceManager.getAccumulatedAbstentionSeconds()
            if (wasPending > 0L) {
                preferenceManager.setAccumulatedAbstentionSeconds(0L)
                Log.d(TAG, "${AppLog.CREDIT} session start → pending abstention invalidated (was ${wasPending}s)")
            }

            // Adaptive Monitoring: Grace Period 알림은 syncState()에서 위험 구간 첫 진입 시 1회 발송.

            Log.d(TAG, "${AppLog.CREDIT} session started → $packageName (balance: ${currentBalanceSeconds}s)")
            return UseResult.Success(currentBalanceSeconds)
        } catch (e: Exception) {
            Log.e(TAG, "${AppLog.CREDIT} startCreditSession failed → exception", e)
            return UseResult.Failure("세션 시작 실패: ${e.message}")
        }
    }

    /**
     * Credit Session을 종료합니다.
     * 사용자가 차단 앱을 이탈할 때 호출됩니다.
     */
    fun endCreditSession() {
        try {
            if (!preferenceManager.isCreditSessionActive()) {
                return
            }
            preferenceManager.setCreditSessionActive(false)
            sessionStartElapsedRealtime = 0L
            preferenceManager.setCreditSessionPackage(null) // Ghost data prevention: clear so termination log/analytics use last session package only
            // Phase 1: 상태 영속화 - 세션 종료 시 lastKnownForegroundPackage 초기화
            preferenceManager.setLastKnownForegroundPackage(null)
            preferenceManager.setCreditAtSessionStartSeconds(0L)
            preferenceManager.setLastKnownAliveSessionTime(0L)
            // State reset immediately after settlement to prevent Ghost Sessions carrying over to next launch.

            // Grace Period 알림 취소
            cancelGracePeriodNotifications()

            val remainingBalanceSeconds = preferenceManager.getTimeCreditBalanceSeconds()
            Log.d(TAG, "${AppLog.CREDIT} session ended → balance: ${remainingBalanceSeconds}s")
        } catch (e: Exception) {
            Log.e(TAG, "${AppLog.CREDIT} endCreditSession failed → exception", e)
        }
    }

    /**
     * 현재 사용 가능한 크레딧 잔액(초)을 조회합니다.
     */
    fun getRemainingCreditSeconds(): Long {
        return preferenceManager.getTimeCreditBalanceSeconds()
    }

    /**
     * 크레딧 사용 가능 여부를 확인합니다. (잔액 > 0일 때 true, 10분 쿨타임 제거됨)
     */
    fun isCreditAvailable(): Boolean {
        return preferenceManager.getTimeCreditBalanceSeconds() > 0L
    }

    /**
     * 강행/철회 등 패널티로 Time Credit 잔액을 차감합니다.
     * 패널티는 분 단위로 전달되며, 내부적으로 초로 환산하여 차감합니다.
     */
    fun applyPenaltyMinutes(minutes: Int): Long {
        if (minutes <= 0) return preferenceManager.getTimeCreditBalanceSeconds()
        val penaltySeconds = (minutes * 60L).coerceAtLeast(0L)
        val balanceSeconds = preferenceManager.getTimeCreditBalanceSeconds()
        val actualDeduct = min(penaltySeconds, balanceSeconds)
        val newBalanceSeconds = (balanceSeconds - actualDeduct).coerceAtLeast(0L)
        preferenceManager.setTimeCreditBalanceSeconds(newBalanceSeconds)
        Log.d(TAG, "${AppLog.CREDIT} penalty ${minutes}min (${actualDeduct}s) → balance ${balanceSeconds}s → ${newBalanceSeconds}s")
        return newBalanceSeconds
    }

    // ══════════════════════════════════════════════════════════════
    // Safeguards: Cool-down 메커니즘 (타임 크레딧 소진 시 10분 쿨타임 기능 제거됨)
    // ══════════════════════════════════════════════════════════════

    /**
     * 현재 쿨다운 중인지 확인합니다.
     * 타임 크레딧 소진 시 10분 쿨타임 기능 제거로 항상 false 반환.
     */
    fun isInCooldown(): Boolean = false

    /**
     * 쿨다운을 시작합니다. (기능 제거로 no-op)
     */
    fun startCooldown(durationMinutes: Int) {
        // 타임 크레딧 소진 시 10분 쿨타임 기능 제거
    }

    /**
     * 쿨다운 남은 시간(밀리초). 기능 제거로 항상 0 반환.
     */
    fun getCooldownRemainingMillis(): Long = 0L

    // ══════════════════════════════════════════════════════════════
    // Safeguards: Grace Period 알림 (기존 AlarmManager 패턴 활용)
    // ══════════════════════════════════════════════════════════════

    /**
     * Grace Period 알림을 스케줄링합니다.
     * m-1 해결: 크레딧 잔액이 임계값보다 작으면 해당 알림을 건너뜁니다.
     */
    private fun scheduleGracePeriodNotifications(startTime: Long, durationMinutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 엣지 케이스 처리 (m-1 해결): durationMinutes < 5일 때 과거 시점 알람 방지
        if (durationMinutes > 5) {
            val fiveMinutesBefore = startTime + ((durationMinutes - 5) * 60 * 1000L)
            scheduleNotificationAlarm(alarmManager, fiveMinutesBefore, REQUEST_CODE_5MIN_BEFORE, ACTION_5MIN_BEFORE)
        }
        if (durationMinutes > 1) {
            val oneMinuteBefore = startTime + ((durationMinutes - 1) * 60 * 1000L)
            scheduleNotificationAlarm(alarmManager, oneMinuteBefore, REQUEST_CODE_1MIN_BEFORE, ACTION_1MIN_BEFORE)
        }
    }

    private fun scheduleNotificationAlarm(alarmManager: AlarmManager, triggerAtMillis: Long, requestCode: Int, action: String) {
        val intent = Intent(context, TimeCreditGracePeriodReceiver::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Log.d(TAG, "${AppLog.CREDIT} grace period notification scheduled → requestCode=$requestCode, action=$action")
        } catch (e: Exception) {
            Log.e(TAG, "${AppLog.CREDIT} grace period schedule failed → exception", e)
        }
    }

    /**
     * Grace Period 알림을 취소합니다.
     */
    private fun cancelGracePeriodNotifications() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        cancelAlarm(alarmManager, REQUEST_CODE_5MIN_BEFORE, ACTION_5MIN_BEFORE)
        cancelAlarm(alarmManager, REQUEST_CODE_1MIN_BEFORE, ACTION_1MIN_BEFORE)
        Log.d(TAG, "${AppLog.CREDIT} session end → grace period notifications cancelled")
    }

    private fun cancelAlarm(alarmManager: AlarmManager, requestCode: Int, action: String) {
        val intent = Intent(context, TimeCreditGracePeriodReceiver::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
