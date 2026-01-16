package com.faust.data.utils

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.faust.models.UserTier
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * 암호화된 SharedPreferences를 사용하는 PreferenceManager입니다.
 * 포인트 조작을 방지하기 위해 EncryptedSharedPreferences를 사용합니다.
 */
class PreferenceManager(context: Context) {
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
        private const val KEY_IS_SERVICE_RUNNING = "is_service_running"
        private const val KEY_PERSONA_TYPE = "persona_type"
        private const val KEY_LAST_SCREEN_OFF_TIME = "last_screen_off_time"
        private const val KEY_LAST_SCREEN_ON_TIME = "last_screen_on_time"
        private const val KEY_TEST_MODE_MAX_APPS = "test_mode_max_apps"
        private const val KEY_AUDIO_BLOCKED_ON_SCREEN_OFF = "audio_blocked_on_screen_off"

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
            prefs.getString(KEY_PERSONA_TYPE, "STREET") ?: "STREET"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get persona type", e)
            "STREET"
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

    // Clear all preferences (for testing/reset)
    fun clearAll() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear preferences", e)
        }
    }
}
