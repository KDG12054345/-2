package com.faust.domain

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import com.faust.data.utils.PreferenceManager
import com.faust.models.UserTier
import com.faust.domain.TimeCreditService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [핵심 이벤트: Time Credit 페널티 이벤트 처리]
 *
 * 역할: 강행 실행 및 철회 시 Time Credit(분) 패널티를 적용합니다.
 * 트리거: GuiltyNegotiationOverlay.onProceed() 또는 onCancel() 호출
 * 처리: 사용자 티어에 따라 패널티 분 계산, PreferenceManager Time Credit 잔액 차감
 *
 * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
 */
class PenaltyService(private val context: Context) {
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(context)
    }

    companion object {
        private const val TAG = "PenaltyService"
        private const val LAUNCH_PENALTY = 6 // 모든 티어: 강행 실행 시 6분 차감
        private const val FREE_TIER_QUIT_PENALTY = 3 // Free 티어: 철회 시 3분 차감
        private const val STANDARD_TIER_QUIT_PENALTY = 3 // Standard 티어: 철회 시 3분 차감
        /** Three short pulses (ms): off, on, off, on, off, on. */
        private val FORCED_TERMINATION_VIBRATION_TIMINGS = longArrayOf(0, 80, 80, 80, 80, 80)
        private val FORCED_TERMINATION_VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
        private val noOpFocusListener = OnAudioFocusChangeListener { }
    }

    /**
     * [핵심 이벤트: Time Credit 페널티 - onProceed 처리]
     * 역할: 앱 강행 실행 시 Time Credit 6분 차감. (GuiltyNegotiationOverlay에서 직접 applyPenaltyMinutes 호출하므로 미사용 가능)
     * @return 패널티 적용 성공 여부
     */
    suspend fun applyLaunchPenalty(packageName: String, appName: String): Boolean {
        return withContext(Dispatchers.IO) {
            val userTier = preferenceManager.getUserTier()
            val penalty = when (userTier) {
                UserTier.FREE -> LAUNCH_PENALTY
                UserTier.STANDARD -> LAUNCH_PENALTY
                UserTier.FAUST_PRO -> LAUNCH_PENALTY // 추후 변경 예정
            }

            applyPenalty(penalty, "앱 강행 실행: $appName")
        }
    }

    /**
     * [핵심 이벤트: Time Credit 페널티 - onCancel 처리]
     * 역할: 앱 철회 시 Time Credit 3분 차감 (Free/Standard). FAUST_PRO는 0분.
     * @return 패널티 적용 성공 여부
     */
    suspend fun applyQuitPenalty(packageName: String, appName: String): Boolean {
        return withContext(Dispatchers.IO) {
            val userTier = preferenceManager.getUserTier()
            val penalty = when (userTier) {
                UserTier.FREE -> FREE_TIER_QUIT_PENALTY
                UserTier.STANDARD -> STANDARD_TIER_QUIT_PENALTY
                UserTier.FAUST_PRO -> 0 // 추후 변경 예정
            }

            if (penalty > 0) {
                Log.w(TAG, "철회 버튼 클릭: ${penalty}분 차감 예정 (티어: ${userTier.name})")
                applyPenalty(penalty, "앱 철회: $appName")
            } else {
                Log.d(TAG, "철회 버튼 클릭: Time Credit 차감 없음 (티어: ${userTier.name})")
                true // FAUST_PRO는 항상 성공으로 간주
            }
        }
    }

    /**
     * [핵심 이벤트: Time Credit 페널티 적용]
     * TimeCreditService.applyPenaltyMinutes() 경유로 적용 (단일 경로 보장).
     * @return 패널티 적용 성공 여부 (true: 1분이라도 차감됨, false: 잔액 부족)
     */
    private suspend fun applyPenalty(penaltyMinutes: Int, reason: String): Boolean {
        if (penaltyMinutes <= 0) return true
        return withContext(Dispatchers.IO) {
            try {
                val before = preferenceManager.getTimeCreditBalanceSeconds()
                val after = TimeCreditService(context).applyPenaltyMinutes(penaltyMinutes)
                if (after < before) {
                    Log.w(TAG, "Time Credit 차감 완료: ${penaltyMinutes}분, 사유: $reason")
                    true
                } else {
                    Log.d(TAG, "Time Credit 부족으로 패널티 적용 불가: 요청 ${penaltyMinutes}분, 현재 ${before}초")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply penalty: penalty=$penaltyMinutes, reason=$reason", e)
                false
            }
        }
    }

    /**
     * [핵심 이벤트: Credit Exhaustion - Forced Media Termination]
     * 트리거: Time Credit Balance == 0 AND isScreenOFF == true AND audioBlocked == true.
     * 동작: 배경 미디어 강제 정지(오디오 포커스 요청 + MEDIA_PAUSE 전송), 3회 짧은 진동, 로그.
     * @param packageName 차단 앱 패키지명 (로깅용)
     */
    fun executeForcedMediaTermination(packageName: String) {
        if (packageName.isBlank()) return
        try {
            Log.d(TAG, "Credit exhausted. Forced audio termination executed for package: $packageName")

            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            // Primary: request exclusive audio focus to force-pause background media
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .build()
                val result = am.requestAudioFocus(focusRequest)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    am.abandonAudioFocusRequest(focusRequest)
                }
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(noOpFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(noOpFocusListener)
            }

            // Secondary: dispatch global KEYCODE_MEDIA_PAUSE so media session is halted
            val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
            }
            context.sendBroadcast(intent)

            // User feedback: three short pulses
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(
                        FORCED_TERMINATION_VIBRATION_TIMINGS,
                        FORCED_TERMINATION_VIBRATION_AMPLITUDES,
                        -1
                    )
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(FORCED_TERMINATION_VIBRATION_TIMINGS, -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Forced media termination failed for package: $packageName", e)
        }
    }
}
