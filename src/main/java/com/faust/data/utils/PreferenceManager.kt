package com.faust.data.utils

import android.content.Context
import android.util.Log
import com.faust.FaustApplication
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.faust.models.UserCreditType
import com.faust.models.UserTier
import com.faust.utils.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * 암호화된 SharedPreferences를 사용하는 PreferenceManager입니다.
 * 포인트 조작을 방지하기 위해 EncryptedSharedPreferences를 사용합니다.
 */
class PreferenceManager(private val context: Context) {
    private val prefs = try {
        createEncryptedSharedPreferences(context)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to regular SharedPreferences", e)
        // 폴백: 일반 SharedPreferences 사용 (보안 경고)
        context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        )
    }

    companion object {
        private const val TAG = "PreferenceManager"
        private const val PREF_NAME = "faust_prefs"
        private const val KEY_USER_TIER = "user_tier"
        private const val KEY_CURRENT_POINTS = "current_points"
        private const val KEY_LAST_MINING_TIME = "last_mining_time"
        private const val KEY_LAST_MINING_APP = "last_mining_app"
        private const val KEY_LAST_RESET_TIME = "last_reset_time"
        private const val KEY_LAST_DAILY_RESET_TIME = "last_daily_reset_time"
        private const val KEY_IS_SERVICE_RUNNING = "is_service_running"
        private const val KEY_PERSONA_TYPE = "persona_type"
        private const val KEY_LAST_SCREEN_OFF_TIME = "last_screen_off_time"
        private const val KEY_LAST_SCREEN_OFF_ELAPSED_REALTIME = "last_screen_off_elapsed_realtime"
        private const val KEY_LAST_SCREEN_ON_TIME = "last_screen_on_time"
        /** Audio Kill 시점(credit 0 + screen OFF): Screen ON 시 이 시점~ON까지 절제 시간으로 정산. */
        private const val KEY_LAST_MINING_RESUME_ELAPSED_REALTIME = "last_mining_resume_elapsed_realtime"
        private const val KEY_TEST_MODE_MAX_APPS = "test_mode_max_apps"
        private const val KEY_AUDIO_BLOCKED_ON_SCREEN_OFF = "audio_blocked_on_screen_off"
        private const val KEY_CUSTOM_DAILY_RESET_TIME = "custom_daily_reset_time"
        // TimeCredit 기본 키
        private const val KEY_TIME_CREDIT_LAST_SYNC_TIME = "time_credit_last_sync_time"
        private const val KEY_TIME_CREDIT_BALANCE = "time_credit_balance"
        private const val KEY_TIME_CREDIT_BALANCE_SECONDS = "time_credit_balance_seconds"
        private const val KEY_TIME_CREDIT_USER_TYPE = "time_credit_user_type"
        private const val KEY_TIME_CREDIT_MAX_CAP = "time_credit_max_cap"
        // 정산 정확도 개선 (C-1): 초 단위 저장 (reward loss 방지)
        private const val KEY_ACCUMULATED_ABSTENTION_MINUTES = "accumulated_abstention_minutes"
        private const val KEY_ACCUMULATED_ABSTENTION_SECONDS = "accumulated_abstention_seconds"
        // Credit Session 모델 (C-2)
        private const val KEY_CREDIT_SESSION_ACTIVE = "credit_session_active"
        private const val KEY_CREDIT_SESSION_START_TIME = "credit_session_start_time"
        private const val KEY_CREDIT_SESSION_START_ELAPSED_REALTIME = "credit_session_start_elapsed_realtime"
        private const val KEY_CREDIT_SESSION_PACKAGE = "credit_session_package"
        private const val KEY_CREDIT_AT_SESSION_START_SECONDS = "credit_at_session_start_seconds"
        private const val KEY_LAST_KNOWN_ALIVE_SESSION_TIME = "last_known_alive_session_time"
        private const val KEY_GOLDEN_TIME_ACTIVE = "golden_time_active"
        private const val KEY_LAST_KNOWN_BALANCE_AT_ALIVE = "last_known_balance_at_alive"
        private const val KEY_LAST_KNOWN_FOREGROUND_PACKAGE = "last_known_foreground_package"
        private const val KEY_METRICS_IDEMPOTENCY_SKIP_COUNT = "metrics_idempotency_skip_count"
        private const val KEY_METRICS_PHYSICAL_LIMIT_GUARD_COUNT = "metrics_physical_limit_guard_count"
        // Cool-down 메커니즘
        private const val KEY_TIME_CREDIT_COOLDOWN_START_TIME = "time_credit_cooldown_start_time"
        private const val KEY_TIME_CREDIT_COOLDOWN_DURATION_MINUTES = "time_credit_cooldown_duration_minutes"
        // Grace Period
        private const val KEY_TIME_CREDIT_USE_START_TIME = "time_credit_use_start_time"
        private const val KEY_STRICT_MODE_ACTIVE = "strict_mode_active"
        private const val KEY_STRICT_MODE_END_TIME = "strict_mode_end_time"
        private const val KEY_EMERGENCY_EXIT_DELAY_MINUTES = "emergency_exit_delay_minutes"
        private const val KEY_EMERGENCY_EXIT_LAST_CLICK_TIME = "emergency_exit_last_click_time"
        private const val KEY_EMERGENCY_EXIT_COOLDOWN_MINUTES = "emergency_exit_cooldown_minutes"
        private const val KEY_PERSIST_DIRTY = "time_credit_persist_dirty"

        /** TimeCredit 잔액(초) 변경 시 emit. replay=1로 새 구독자가 최신 잔액을 받음. */
        private val timeCreditBalanceFlow = MutableSharedFlow<Long>(replay = 1)

        /** In-memory cache: 초당 저장 방지, persist는 screen-off / 1분 간격 / app exit 시에만. */
        @Volatile var cachedBalanceSeconds: Long? = null
        @Volatile var cachedAccumulatedSeconds: Long? = null
        internal val timeCreditCacheLock = Any()

        /** TimeCredit 잔액(초) Flow. 초기값 emit 후 balance 변경 시마다 갱신. */
        fun getTimeCreditBalanceFlow(prefs: android.content.SharedPreferences, readBalance: () -> Long): Flow<Long> = flow {
            emit(readBalance())
            emitAll(timeCreditBalanceFlow)
        }

        internal fun emitTimeCreditBalance(balanceSeconds: Long) {
            timeCreditBalanceFlow.tryEmit(balanceSeconds.coerceAtLeast(0L))
        }

        /**
         * 싱글톤 인스턴스를 반환합니다.
         * FaustApplication에서 관리하는 단일 인스턴스를 사용하여
         * EncryptedSharedPreferences 중복 초기화를 방지합니다.
         *
         * @param context 어떤 Context든 가능 (내부에서 applicationContext 추출).
         *                GuiltyNegotiationOverlay 등 Application 접근이 어려운 컴포넌트에서도 사용 가능.
         */
        fun getInstance(context: Context): PreferenceManager {
            return (context.applicationContext as FaustApplication).preferenceManager
        }

        /**
         * EncryptedSharedPreferences 인스턴스를 생성합니다.
         */
        private fun createEncryptedSharedPreferences(context: Context): android.content.SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    // User Tier
    fun getUserTier(): UserTier {
        val tierName = prefs.getString(KEY_USER_TIER, UserTier.FREE.name) ?: UserTier.FREE.name
        return try {
            UserTier.valueOf(tierName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid user tier: $tierName, defaulting to FREE", e)
            UserTier.FREE
        }
    }

    fun setUserTier(tier: UserTier) {
        try {
            prefs.edit().putString(KEY_USER_TIER, tier.name).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set user tier", e)
        }
    }

    // Current Points
    fun getCurrentPoints(): Int {
        return try {
            prefs.getInt(KEY_CURRENT_POINTS, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current points", e)
            0
        }
    }

    fun setCurrentPoints(points: Int) {
        try {
            prefs.edit().putInt(KEY_CURRENT_POINTS, points.coerceAtLeast(0)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set current points", e)
        }
    }

    fun addPoints(points: Int) {
        val current = getCurrentPoints()
        setCurrentPoints(current + points)
    }

    fun subtractPoints(points: Int) {
        val current = getCurrentPoints()
        setCurrentPoints((current - points).coerceAtLeast(0))
    }

    // Mining Time Tracking
    fun getLastMiningTime(): Long {
        return try {
            prefs.getLong(KEY_LAST_MINING_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last mining time", e)
            0L
        }
    }

    fun setLastMiningTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_MINING_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last mining time", e)
        }
    }

    fun getLastMiningApp(): String? {
        return try {
            prefs.getString(KEY_LAST_MINING_APP, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last mining app", e)
            null
        }
    }

    fun setLastMiningApp(packageName: String?) {
        try {
            prefs.edit().putString(KEY_LAST_MINING_APP, packageName).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last mining app", e)
        }
    }

    // Weekly Reset
    fun getLastResetTime(): Long {
        return try {
            prefs.getLong(KEY_LAST_RESET_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last reset time", e)
            0L
        }
    }

    fun setLastResetTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_RESET_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last reset time", e)
        }
    }

    /** 일일 초기화 쿨다운용. 성공 시에만 setLastDailyResetTime 호출. */
    fun getLastDailyResetTime(): Long {
        return try {
            prefs.getLong(KEY_LAST_DAILY_RESET_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last daily reset time", e)
            0L
        }
    }

    fun setLastDailyResetTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_DAILY_RESET_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last daily reset time", e)
        }
    }

    // Service State
    fun isServiceRunning(): Boolean {
        return try {
            prefs.getBoolean(KEY_IS_SERVICE_RUNNING, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get service running state", e)
            false
        }
    }

    fun setServiceRunning(running: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_IS_SERVICE_RUNNING, running).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set service running state", e)
        }
    }

    // Persona Type
    fun getPersonaTypeString(): String {
        return try {
            prefs.getString(KEY_PERSONA_TYPE, "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get persona type", e)
            ""
        }
    }

    fun setPersonaType(type: String) {
        try {
            prefs.edit().putString(KEY_PERSONA_TYPE, type).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set persona type", e)
        }
    }

    // Screen Event Tracking
    fun getLastScreenOffTime(): Long {
        return try {
            prefs.getLong(KEY_LAST_SCREEN_OFF_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last screen off time", e)
            0L
        }
    }

    fun setLastScreenOffTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_SCREEN_OFF_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last screen off time", e)
        }
    }

    /** 단조 시간(Monotonic): 화면 OFF 시점의 elapsedRealtime. 시스템 시계 조작 검증용. */
    fun getLastScreenOffElapsedRealtime(): Long {
        return try {
            prefs.getLong(KEY_LAST_SCREEN_OFF_ELAPSED_REALTIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last screen off elapsed realtime", e)
            0L
        }
    }

    fun setLastScreenOffElapsedRealtime(elapsedRealtime: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_SCREEN_OFF_ELAPSED_REALTIME, elapsedRealtime).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last screen off elapsed realtime", e)
        }
    }

    /** Credit 0 + Screen OFF에서 오디오 킬 후 채굴 재개 시점(elapsedRealtime). Screen ON 시 이 시점~ON까지 절제로 정산. */
    fun getLastMiningResumeElapsedRealtime(): Long {
        return try {
            prefs.getLong(KEY_LAST_MINING_RESUME_ELAPSED_REALTIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last mining resume elapsed realtime", e)
            0L
        }
    }

    fun setLastMiningResumeElapsedRealtime(elapsedRealtime: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_MINING_RESUME_ELAPSED_REALTIME, elapsedRealtime).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last mining resume elapsed realtime", e)
        }
    }

    fun getLastScreenOnTime(): Long {
        return try {
            prefs.getLong(KEY_LAST_SCREEN_ON_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last screen on time", e)
            0L
        }
    }

    fun setLastScreenOnTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_SCREEN_ON_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last screen on time", e)
        }
    }

    // Audio Blocked State on Screen Off
    /**
     * 화면 OFF 시 차단 앱에서 오디오가 재생 중이었는지 조회합니다.
     * @return 화면 OFF 시 차단 앱 오디오 재생 중이었으면 true
     */
    fun wasAudioBlockedOnScreenOff(): Boolean {
        return try {
            prefs.getBoolean(KEY_AUDIO_BLOCKED_ON_SCREEN_OFF, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get audio blocked on screen off state", e)
            false
        }
    }

    /**
     * 화면 OFF 시 차단 앱에서 오디오가 재생 중이었는지 저장합니다.
     * @param wasBlocked 화면 OFF 시 차단 앱 오디오 재생 중이었으면 true
     */
    fun setAudioBlockedOnScreenOff(wasBlocked: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_AUDIO_BLOCKED_ON_SCREEN_OFF, wasBlocked).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set audio blocked on screen off state", e)
        }
    }

    // App-specific Last Settled Time (for preventing duplicate point calculation)
    /**
     * 앱별로 마지막 정산 시점의 총 사용 시간(분)을 조회합니다.
     * @param packageName 앱 패키지 이름
     * @return 마지막 정산 시점의 총 사용 시간(분), 없으면 0
     */
    fun getLastSettledTime(packageName: String): Long {
        return try {
            val key = "last_settled_time_$packageName"
            prefs.getLong(key, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last settled time for $packageName", e)
            0L
        }
    }

    /**
     * 앱별로 마지막 정산 시점의 총 사용 시간(분)을 저장합니다.
     * @param packageName 앱 패키지 이름
     * @param timeInMinutes 마지막 정산 시점의 총 사용 시간(분)
     */
    fun setLastSettledTime(packageName: String, timeInMinutes: Long) {
        try {
            val key = "last_settled_time_$packageName"
            prefs.edit().putLong(key, timeInMinutes).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last settled time for $packageName", e)
        }
    }

    // Test Mode (for testing on real device)
    /**
     * 테스트 모드 최대 차단 앱 개수를 조회합니다.
     * @return 테스트 모드 최대 앱 개수, 설정되지 않았거나 비활성화된 경우 null 반환
     */
    fun getTestModeMaxApps(): Int? {
        return try {
            val value = prefs.getInt(KEY_TEST_MODE_MAX_APPS, -1)
            if (value > 0) value else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get test mode max apps", e)
            null
        }
    }

    /**
     * 테스트 모드 최대 차단 앱 개수를 설정합니다.
     * @param maxApps 최대 앱 개수 (null이면 테스트 모드 비활성화)
     */
    fun setTestModeMaxApps(maxApps: Int?) {
        try {
            if (maxApps != null && maxApps > 0) {
                prefs.edit().putInt(KEY_TEST_MODE_MAX_APPS, maxApps).apply()
            } else {
                prefs.edit().putInt(KEY_TEST_MODE_MAX_APPS, -1).apply() // -1로 설정하여 비활성화
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set test mode max apps", e)
        }
    }

    // Custom Daily Reset Time
    /**
     * 사용자 지정 일일 리셋 시간을 조회합니다.
     * @return "HH:mm" 형식의 시간 문자열 (기본값: "00:00")
     */
    fun getCustomDailyResetTime(): String {
        return try {
            prefs.getString(KEY_CUSTOM_DAILY_RESET_TIME, "00:00") ?: "00:00"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get custom daily reset time", e)
            "00:00"
        }
    }

    /**
     * 사용자 지정 일일 리셋 시간을 저장합니다.
     * @param time "HH:mm" 형식의 시간 문자열 (예: "02:00", "14:30")
     */
    fun setCustomDailyResetTime(time: String) {
        try {
            // 시간 형식 검증
            TimeUtils.parseTimeString(time) // 형식이 잘못되면 예외 발생
            prefs.edit().putString(KEY_CUSTOM_DAILY_RESET_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set custom daily reset time: $time", e)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TimeCredit 기본 메서드
    // ══════════════════════════════════════════════════════════════

    /** 마지막 크레딧 정산 시점. SystemClock.elapsedRealtime() 기준 저장(시계 조작 방지). */
    fun getTimeCreditLastSyncTime(): Long {
        return try {
            prefs.getLong(KEY_TIME_CREDIT_LAST_SYNC_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get time credit last sync time", e)
            0L
        }
    }

    /** 마지막 크레딧 정산 시점 저장. 값은 SystemClock.elapsedRealtime()으로 설정할 것. */
    fun setTimeCreditLastSyncTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_TIME_CREDIT_LAST_SYNC_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set time credit last sync time", e)
        }
    }

    /** 디스크에서 잔액(초) 읽기. 마이그레이션(분→초) 포함. 캐시 갱신 없음. */
    private fun readBalanceFromPrefsInternal(): Long {
        return try {
            if (!prefs.contains(KEY_TIME_CREDIT_BALANCE_SECONDS)) {
                val oldMinutes = prefs.getInt(KEY_TIME_CREDIT_BALANCE, 0)
                val seconds = (oldMinutes * 60L).coerceAtLeast(0L)
                prefs.edit().putLong(KEY_TIME_CREDIT_BALANCE_SECONDS, seconds).apply()
                seconds
            } else {
                prefs.getLong(KEY_TIME_CREDIT_BALANCE_SECONDS, 0L).coerceAtLeast(0L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read time credit balance from prefs", e)
            0L
        }
    }

    /** 디스크에서 누적 절제(초) 읽기. 마이그레이션 포함. 캐시 갱신 없음. */
    private fun readAccumulatedFromPrefsInternal(): Long {
        return try {
            if (!prefs.contains(KEY_ACCUMULATED_ABSTENTION_SECONDS)) {
                val oldMinutes = prefs.getInt(KEY_ACCUMULATED_ABSTENTION_MINUTES, 0)
                val seconds = (oldMinutes * 60L).coerceAtLeast(0L)
                prefs.edit().putLong(KEY_ACCUMULATED_ABSTENTION_SECONDS, seconds).apply()
                seconds
            } else {
                prefs.getLong(KEY_ACCUMULATED_ABSTENTION_SECONDS, 0L).coerceAtLeast(0L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read accumulated abstention from prefs", e)
            0L
        }
    }

    /** 현재 크레딧 잔액(초). 캐시 우선, 없으면 디스크에서 로드 후 캐시. */
    fun getTimeCreditBalanceSeconds(): Long {
        synchronized(Companion.timeCreditCacheLock) {
            Companion.cachedBalanceSeconds?.let { return it }
            val v = readBalanceFromPrefsInternal()
            Companion.cachedBalanceSeconds = v
            return v
        }
    }

    /** 크레딧 잔액(초) 갱신. 메모리 캐시 + Flow만 갱신, 디스크는 persist 시점에 저장. */
    fun setTimeCreditBalanceSeconds(balanceSeconds: Long) {
        val value = balanceSeconds.coerceAtLeast(0L)
        synchronized(Companion.timeCreditCacheLock) {
            Companion.cachedBalanceSeconds = value
        }
        Companion.emitTimeCreditBalance(value)
    }

    /**
     * Time Credit 캐시 값을 SharedPreferences에 저장합니다.
     *
     * @param synchronous true인 경우 commit() 사용 (동기 저장, onDestroy 경로).
     *                    false인 경우 apply() 사용 (비동기 저장, 기본값).
     * commit() 실패 시 1회 재시도 후 복구 플래그 설정. apply()까지 실패 시 비상 파일 덤프.
     */
    fun persistTimeCreditValues(synchronous: Boolean = false) {
        synchronized(Companion.timeCreditCacheLock) {
            val balance = Companion.cachedBalanceSeconds ?: readBalanceFromPrefsInternal()
            val accumulated = Companion.cachedAccumulatedSeconds ?: readAccumulatedFromPrefsInternal()
            try {
                if (synchronous) {
                    val startMs = System.currentTimeMillis()
                    var success = prefs.edit()
                        .putLong(KEY_TIME_CREDIT_BALANCE_SECONDS, balance)
                        .putLong(KEY_ACCUMULATED_ABSTENTION_SECONDS, accumulated)
                        .commit()
                    var elapsed = System.currentTimeMillis() - startMs
                    if (!success) {
                        Log.w(TAG, "commit() 1차 실패 (${elapsed}ms), 재시도...")
                        success = prefs.edit()
                            .putLong(KEY_TIME_CREDIT_BALANCE_SECONDS, balance)
                            .putLong(KEY_ACCUMULATED_ABSTENTION_SECONDS, accumulated)
                            .commit()
                        elapsed = System.currentTimeMillis() - startMs
                    }
                    if (!success) {
                        Log.e(TAG, "commit() 최종 실패! 복구 플래그 설정 (balance=$balance, accumulated=$accumulated)")
                        try {
                            prefs.edit().putBoolean(KEY_PERSIST_DIRTY, true).apply()
                        } catch (_: Throwable) {
                            try {
                                val dumpFile = java.io.File(context.filesDir, "tc_emergency_dump.txt")
                                dumpFile.writeText("balance=$balance,accumulated=$accumulated,ts=${System.currentTimeMillis()}\nEOF")
                                Log.e(TAG, "비상 파일 덤프 완료: ${dumpFile.absolutePath}")
                            } catch (_: Throwable) { }
                        }
                    } else if (elapsed > 50) {
                        Log.w(TAG, "commit() 소요 시간 경고: ${elapsed}ms")
                    } else { /* success, no log */ }
                } else {
                    prefs.edit()
                        .putLong(KEY_TIME_CREDIT_BALANCE_SECONDS, balance)
                        .putLong(KEY_ACCUMULATED_ABSTENTION_SECONDS, accumulated)
                        .apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist time credit values", e)
            }
        }
    }

    fun isPersistDirty(): Boolean = try {
        prefs.getBoolean(KEY_PERSIST_DIRTY, false)
    } catch (_: Exception) { false }

    fun clearPersistDirtyFlag() {
        try { prefs.edit().putBoolean(KEY_PERSIST_DIRTY, false).apply() } catch (_: Exception) { }
    }

    /**
     * Phase 3: 원자적 업데이트 - 잔액과 lastSyncTime을 하나의 트랜잭션으로 저장.
     * 
     * @param balanceSeconds 저장할 잔액(초)
     * @param lastSyncTimeElapsedRealtime 저장할 마지막 정산 시점 (elapsedRealtime 기준)
     * @param synchronous true인 경우 commit() 사용 (동기 저장), false인 경우 apply() 사용 (비동기 저장)
     */
    fun persistBalanceAndLastSyncTime(balanceSeconds: Long, lastSyncTimeElapsedRealtime: Long, synchronous: Boolean = false) {
        synchronized(Companion.timeCreditCacheLock) {
            val balance = balanceSeconds.coerceAtLeast(0L)
            try {
                if (synchronous) {
                    val startMs = System.currentTimeMillis()
                    var success = prefs.edit()
                        .putLong(KEY_TIME_CREDIT_BALANCE_SECONDS, balance)
                        .putLong(KEY_TIME_CREDIT_LAST_SYNC_TIME, lastSyncTimeElapsedRealtime)
                        .commit()
                    var elapsed = System.currentTimeMillis() - startMs
                    if (!success) {
                        Log.w(TAG, "${AppLog.CONSISTENCY} persistBalanceAndLastSyncTime commit() 1차 실패 (${elapsed}ms), 재시도...")
                        success = prefs.edit()
                            .putLong(KEY_TIME_CREDIT_BALANCE_SECONDS, balance)
                            .putLong(KEY_TIME_CREDIT_LAST_SYNC_TIME, lastSyncTimeElapsedRealtime)
                            .commit()
                        elapsed = System.currentTimeMillis() - startMs
                    }
                    if (!success) {
                        Log.e(TAG, "${AppLog.CONSISTENCY} persistBalanceAndLastSyncTime commit() 최종 실패! 복구 플래그 설정 (balance=$balance, lastSync=$lastSyncTimeElapsedRealtime)")
                        try {
                            prefs.edit().putBoolean(KEY_PERSIST_DIRTY, true).apply()
                        } catch (_: Throwable) {
                            try {
                                val dumpFile = java.io.File(context.filesDir, "tc_emergency_dump.txt")
                                dumpFile.writeText("balance=$balance,lastSync=$lastSyncTimeElapsedRealtime,ts=${System.currentTimeMillis()}\nEOF")
                                Log.e(TAG, "${AppLog.CONSISTENCY} 비상 파일 덤프 완료: ${dumpFile.absolutePath}")
                            } catch (_: Throwable) { }
                        }
                    } else if (elapsed > 50) {
                        Log.w(TAG, "${AppLog.CONSISTENCY} persistBalanceAndLastSyncTime commit() 소요 시간 경고: ${elapsed}ms")
                    }
                    // 성공 시 캐시 갱신
                    if (success) {
                        Companion.cachedBalanceSeconds = balance
                    } else {
                        // commit() 실패 시에도 캐시는 갱신하지 않음 (데이터 정합성 보호)
                    }
                } else {
                    prefs.edit()
                        .putLong(KEY_TIME_CREDIT_BALANCE_SECONDS, balance)
                        .putLong(KEY_TIME_CREDIT_LAST_SYNC_TIME, lastSyncTimeElapsedRealtime)
                        .apply()
                    // 비동기 저장이므로 캐시만 갱신
                    Companion.cachedBalanceSeconds = balance
                }
            } catch (e: Exception) {
                Log.e(TAG, "${AppLog.CONSISTENCY} Failed to persist balance and last sync time", e)
            }
        }
    }

    /** TimeCredit 잔액(초) Flow. 초기값 + setTimeCreditBalanceSeconds 호출 시마다 갱신. */
    fun getTimeCreditBalanceFlow(): Flow<Long> = Companion.getTimeCreditBalanceFlow(prefs) { getTimeCreditBalanceSeconds() }

    /** 사용자 크레딧 타입을 조회합니다. */
    fun getTimeCreditUserType(): UserCreditType {
        return try {
            val typeName = prefs.getString(KEY_TIME_CREDIT_USER_TYPE, UserCreditType.PRO.name)
                ?: UserCreditType.PRO.name
            UserCreditType.valueOf(typeName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get time credit user type", e)
            UserCreditType.PRO
        }
    }

    /** 사용자 크레딧 타입을 저장합니다. */
    fun setTimeCreditUserType(type: UserCreditType) {
        try {
            prefs.edit().putString(KEY_TIME_CREDIT_USER_TYPE, type.name).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set time credit user type", e)
        }
    }

    /** 최대 크레딧 누적 가능 시간(분)을 조회합니다. 기본값 120분. (캡은 분 단위 유지) */
    fun getTimeCreditMaxCap(): Int {
        return try {
            prefs.getInt(KEY_TIME_CREDIT_MAX_CAP, 120)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get time credit max cap", e)
            120
        }
    }

    /** 최대 크레딧 누적 가능 시간(분)을 저장합니다. */
    fun setTimeCreditMaxCap(cap: Int) {
        try {
            prefs.edit().putInt(KEY_TIME_CREDIT_MAX_CAP, cap.coerceAtLeast(1)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set time credit max cap", e)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 정산 정확도 개선 (C-1): 누적 절제 시간 (초 단위, reward loss 방지)
    // ══════════════════════════════════════════════════════════════

    /** 누적 절제 시간(초). 캐시 우선, 없으면 디스크에서 로드 후 캐시. */
    fun getAccumulatedAbstentionSeconds(): Long {
        synchronized(Companion.timeCreditCacheLock) {
            Companion.cachedAccumulatedSeconds?.let { return it }
            val v = readAccumulatedFromPrefsInternal()
            Companion.cachedAccumulatedSeconds = v
            return v
        }
    }

    /** 누적 절제 시간(초) 갱신. 메모리 캐시만 갱신, 디스크는 persist 시점에 저장. */
    fun setAccumulatedAbstentionSeconds(seconds: Long) {
        val value = seconds.coerceAtLeast(0L)
        synchronized(Companion.timeCreditCacheLock) {
            Companion.cachedAccumulatedSeconds = value
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Credit Session 모델 (C-2)
    // ══════════════════════════════════════════════════════════════

    /** 크레딧 세션이 활성 상태인지 조회합니다. */
    fun isCreditSessionActive(): Boolean {
        return try {
            prefs.getBoolean(KEY_CREDIT_SESSION_ACTIVE, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get credit session active state", e)
            false
        }
    }

    /** 크레딧 세션 활성 상태를 저장합니다. */
    fun setCreditSessionActive(active: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_CREDIT_SESSION_ACTIVE, active).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set credit session active state", e)
        }
    }

    /** 크레딧 세션 시작 시간을 조회합니다. */
    fun getCreditSessionStartTime(): Long {
        return try {
            prefs.getLong(KEY_CREDIT_SESSION_START_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get credit session start time", e)
            0L
        }
    }

    /** 크레딧 세션 시작 시간을 저장합니다. */
    fun setCreditSessionStartTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_CREDIT_SESSION_START_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set credit session start time", e)
        }
    }

    /** 크레딧 세션 시작 시점의 elapsedRealtime. 사용량/복구 계산용(시계 조작 방지). */
    fun getCreditSessionStartElapsedRealtime(): Long {
        return try {
            prefs.getLong(KEY_CREDIT_SESSION_START_ELAPSED_REALTIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get credit session start elapsed realtime", e)
            0L
        }
    }

    fun setCreditSessionStartElapsedRealtime(elapsedRealtime: Long) {
        try {
            prefs.edit().putLong(KEY_CREDIT_SESSION_START_ELAPSED_REALTIME, elapsedRealtime).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set credit session start elapsed realtime", e)
        }
    }

    /** 크레딧 세션 중인 앱 패키지명을 조회합니다. */
    fun getCreditSessionPackage(): String? {
        return try {
            prefs.getString(KEY_CREDIT_SESSION_PACKAGE, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get credit session package", e)
            null
        }
    }

    /** 크레딧 세션 중인 앱 패키지명을 저장합니다. */
    fun setCreditSessionPackage(packageName: String?) {
        try {
            if (packageName != null) {
                prefs.edit().putString(KEY_CREDIT_SESSION_PACKAGE, packageName).apply()
            } else {
                prefs.edit().remove(KEY_CREDIT_SESSION_PACKAGE).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set credit session package", e)
        }
    }

    /** 마지막으로 알려진 포그라운드 패키지명을 조회합니다. Phase 1: 상태 영속화. */
    fun getLastKnownForegroundPackage(): String? {
        return try {
            prefs.getString(KEY_LAST_KNOWN_FOREGROUND_PACKAGE, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last known foreground package", e)
            null
        }
    }

    /** 마지막으로 알려진 포그라운드 패키지명을 저장합니다. Phase 1: 상태 영속화. */
    fun setLastKnownForegroundPackage(packageName: String?) {
        try {
            if (packageName != null) {
                prefs.edit().putString(KEY_LAST_KNOWN_FOREGROUND_PACKAGE, packageName).apply()
            } else {
                prefs.edit().remove(KEY_LAST_KNOWN_FOREGROUND_PACKAGE).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last known foreground package", e)
        }
    }

    /** 세션 시작 시점의 잔액(초). 실시간 소진 체크 및 1:1 차감용. */
    fun getCreditAtSessionStartSeconds(): Long {
        return try {
            prefs.getLong(KEY_CREDIT_AT_SESSION_START_SECONDS, 0L).coerceAtLeast(0L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get credit at session start", e)
            0L
        }
    }

    fun setCreditAtSessionStartSeconds(seconds: Long) {
        try {
            prefs.edit().putLong(KEY_CREDIT_AT_SESSION_START_SECONDS, seconds.coerceAtLeast(0L)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set credit at session start", e)
        }
    }

    /** 세션 중 30초마다 갱신. SystemClock.elapsedRealtime() 기준 저장(복구 계산용). */
    fun getLastKnownAliveSessionTime(): Long {
        return try {
            prefs.getLong(KEY_LAST_KNOWN_ALIVE_SESSION_TIME, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last known alive session time", e)
            0L
        }
    }

    /** elapsedRealtime() 값으로 저장할 것. */
    fun setLastKnownAliveSessionTime(timeMillis: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_KNOWN_ALIVE_SESSION_TIME, timeMillis).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last known alive session time", e)
        }
    }

    /** 골든 타임(1분 전 구간) 활성 여부. 프로세스 종료 후 복구 시 체크포인트 사용 여부 판단용. */
    fun isGoldenTimeActive(): Boolean {
        return try {
            prefs.getBoolean(KEY_GOLDEN_TIME_ACTIVE, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get golden time active", e)
            false
        }
    }

    fun setGoldenTimeActive(active: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_GOLDEN_TIME_ACTIVE, active).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set golden time active", e)
        }
    }

    /** lastKnownAlive 시점의 잔액(초). 골든 타임 복구 시 usage 재계산 없이 이 값으로 복구. */
    fun getLastKnownBalanceAtAlive(): Long {
        return try {
            prefs.getLong(KEY_LAST_KNOWN_BALANCE_AT_ALIVE, 0L).coerceAtLeast(0L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last known balance at alive", e)
            0L
        }
    }

    fun setLastKnownBalanceAtAlive(balanceSeconds: Long) {
        try {
            prefs.edit().putLong(KEY_LAST_KNOWN_BALANCE_AT_ALIVE, balanceSeconds.coerceAtLeast(0L)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last known balance at alive", e)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TimeCredit Cool-down 메커니즘
    // ══════════════════════════════════════════════════════════════

    /** 크레딧 소진 쿨다운 시작 시간을 조회합니다. */
    fun getTimeCreditCooldownStartTime(): Long {
        return try {
            prefs.getLong(KEY_TIME_CREDIT_COOLDOWN_START_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get time credit cooldown start time", e)
            0L
        }
    }

    /** 크레딧 소진 쿨다운 시작 시간을 저장합니다. */
    fun setTimeCreditCooldownStartTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_TIME_CREDIT_COOLDOWN_START_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set time credit cooldown start time", e)
        }
    }

    /** 크레딧 소진 쿨다운 지속 시간(분)을 조회합니다. */
    fun getTimeCreditCooldownDurationMinutes(): Int {
        return try {
            prefs.getInt(KEY_TIME_CREDIT_COOLDOWN_DURATION_MINUTES, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get time credit cooldown duration minutes", e)
            0
        }
    }

    /** 크레딧 소진 쿨다운 지속 시간(분)을 저장합니다. */
    fun setTimeCreditCooldownDurationMinutes(minutes: Int) {
        try {
            prefs.edit().putInt(KEY_TIME_CREDIT_COOLDOWN_DURATION_MINUTES, minutes.coerceAtLeast(0)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set time credit cooldown duration minutes", e)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TimeCredit Grace Period
    // ══════════════════════════════════════════════════════════════

    /** 크레딧 사용 시작 시간을 조회합니다. */
    fun getTimeCreditUseStartTime(): Long {
        return try {
            prefs.getLong(KEY_TIME_CREDIT_USE_START_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get time credit use start time", e)
            0L
        }
    }

    /** 크레딧 사용 시작 시간을 저장합니다. */
    fun setTimeCreditUseStartTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_TIME_CREDIT_USE_START_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set time credit use start time", e)
        }
    }

    // Strict Mode
    /**
     * 엄격모드 활성 상태를 조회합니다.
     * @return 엄격모드가 활성화되어 있으면 true
     */
    fun isStrictModeActive(): Boolean {
        return try {
            prefs.getBoolean(KEY_STRICT_MODE_ACTIVE, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get strict mode active state", e)
            false
        }
    }

    /**
     * 엄격모드 활성 상태를 저장합니다.
     * @param active 엄격모드가 활성화되어 있으면 true
     */
    fun setStrictModeActive(active: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_STRICT_MODE_ACTIVE, active).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set strict mode active state", e)
        }
    }

    /**
     * 엄격모드 종료 시간을 조회합니다.
     * @return 엄격모드 종료 시간 (timestamp), 없으면 0
     */
    fun getStrictModeEndTime(): Long {
        return try {
            prefs.getLong(KEY_STRICT_MODE_END_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get strict mode end time", e)
            0L
        }
    }

    /**
     * 엄격모드 종료 시간을 저장합니다.
     * @param endTime 엄격모드 종료 시간 (timestamp)
     */
    fun setStrictModeEndTime(endTime: Long) {
        try {
            prefs.edit().putLong(KEY_STRICT_MODE_END_TIME, endTime).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set strict mode end time", e)
        }
    }

    /**
     * 비상구 대기 시간을 조회합니다.
     * TODO: 사용자 지정 대기 시간 기능 구현
     * @return 비상구 대기 시간 (분), 기본값: 10분
     */
    fun getEmergencyExitDelayMinutes(): Int {
        return try {
            val minutes = prefs.getInt(KEY_EMERGENCY_EXIT_DELAY_MINUTES, 10)
            if (minutes > 0) minutes else 10
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get emergency exit delay minutes", e)
            10
        }
    }

    /**
     * 비상구 대기 시간을 저장합니다.
     * TODO: 사용자 지정 대기 시간 기능 구현
     * @param minutes 비상구 대기 시간 (분)
     */
    fun setEmergencyExitDelayMinutes(minutes: Int) {
        try {
            prefs.edit().putInt(KEY_EMERGENCY_EXIT_DELAY_MINUTES, minutes.coerceAtLeast(1)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set emergency exit delay minutes", e)
        }
    }

    /**
     * 비상구 마지막 클릭 시각을 조회합니다.
     * @return 비상구 마지막 클릭 시각 (timestamp), 없으면 0
     */
    fun getEmergencyExitLastClickTime(): Long {
        return try {
            prefs.getLong(KEY_EMERGENCY_EXIT_LAST_CLICK_TIME, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get emergency exit last click time", e)
            0L
        }
    }

    /**
     * 비상구 마지막 클릭 시각을 저장합니다.
     * @param time 비상구 마지막 클릭 시각 (timestamp)
     */
    fun setEmergencyExitLastClickTime(time: Long) {
        try {
            prefs.edit().putLong(KEY_EMERGENCY_EXIT_LAST_CLICK_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set emergency exit last click time", e)
        }
    }

    /**
     * 비상구 쿨타임을 조회합니다.
     * @return 비상구 쿨타임 (분), 없으면 0
     */
    fun getEmergencyExitCooldownMinutes(): Int {
        return try {
            prefs.getInt(KEY_EMERGENCY_EXIT_COOLDOWN_MINUTES, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get emergency exit cooldown minutes", e)
            0
        }
    }

    /**
     * 비상구 쿨타임을 저장합니다.
     * @param minutes 비상구 쿨타임 (분)
     */
    fun setEmergencyExitCooldownMinutes(minutes: Int) {
        try {
            prefs.edit().putInt(KEY_EMERGENCY_EXIT_COOLDOWN_MINUTES, minutes.coerceAtLeast(0)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set emergency exit cooldown minutes", e)
        }
    }

    // Clear all preferences (for testing/reset)
    fun clearAll() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear preferences", e)
        }
    }

    /**
     * Phase 3: 운영 지표 수집 - 메트릭 저장
     */
    fun saveMetrics(idempotencySkipCount: Long, physicalLimitGuardCount: Long) {
        try {
            prefs.edit()
                .putLong(KEY_METRICS_IDEMPOTENCY_SKIP_COUNT, idempotencySkipCount)
                .putLong(KEY_METRICS_PHYSICAL_LIMIT_GUARD_COUNT, physicalLimitGuardCount)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metrics", e)
        }
    }

    /**
     * Phase 3: 운영 지표 수집 - 멱등성 스킵 횟수 조회
     */
    fun getMetricsIdempotencySkipCount(): Long {
        return try {
            prefs.getLong(KEY_METRICS_IDEMPOTENCY_SKIP_COUNT, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get metrics idempotency skip count", e)
            0L
        }
    }

    /**
     * Phase 3: 운영 지표 수집 - 세이프 가드 발동 횟수 조회
     */
    fun getMetricsPhysicalLimitGuardCount(): Long {
        return try {
            prefs.getLong(KEY_METRICS_PHYSICAL_LIMIT_GUARD_COUNT, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get metrics physical limit guard count", e)
            0L
        }
    }
}
