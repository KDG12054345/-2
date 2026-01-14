package com.faust.domain.persona

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.faust.domain.persona.handlers.AudioHandler
import com.faust.domain.persona.handlers.HapticHandler
import com.faust.domain.persona.handlers.VisualHandler
import kotlinx.coroutines.*

/**
 * 페르소나 엔진
 * 기기 상태와 페르소나 프로필을 조합하여 최적의 피드백 모드를 결정하고
 * 각 핸들러에게 실행 명령을 내립니다.
 */
class PersonaEngine(
    private val personaProvider: PersonaProvider,
    private val visualHandler: VisualHandler,
    private val hapticHandler: HapticHandler,
    private val audioHandler: AudioHandler,
    private val context: Context
) {
    companion object {
        private const val TAG = "PersonaEngine"
    }
    
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * 현재 페르소나 프로필을 반환합니다.
     */
    fun getPersonaProfile(): PersonaProfile {
        return personaProvider.getPersonaProfile()
    }
    
    /**
     * 피드백을 실행합니다.
     * 
     * @param profile 페르소나 프로필
     * @param textPrompt 문구를 표시할 TextView
     * @param editInput 사용자 입력을 받을 EditText
     * @param proceedButton 입력 검증에 따라 활성화될 버튼
     */
    suspend fun executeFeedback(
        profile: PersonaProfile,
        textPrompt: TextView,
        editInput: EditText,
        proceedButton: Button
    ) {
        try {
            val feedbackMode = determineFeedbackMode()
            Log.d(TAG, "Feedback mode determined: $feedbackMode")
            
            // 시각 피드백 (항상 실행) - 메인 스레드에서 즉시 실행
            visualHandler.displayPrompt(
                profile.promptText,
                textPrompt,
                editInput,
                proceedButton
            )
            visualHandler.setupInputValidation(
                editInput,
                profile.promptText,
                proceedButton
            )
            
            // 진동 피드백
            if (feedbackMode == FeedbackMode.VIBRATION ||
                feedbackMode == FeedbackMode.TEXT_VIBRATION ||
                feedbackMode == FeedbackMode.ALL
            ) {
                coroutineScope.launch(Dispatchers.Default) {
                    hapticHandler.startVibrationLoop(profile.vibrationPattern)
                }
            }
            
            // 오디오 피드백 - 텍스트 표시와 동시에 시작되도록 비동기 실행
            if (feedbackMode == FeedbackMode.AUDIO ||
                feedbackMode == FeedbackMode.TEXT_AUDIO ||
                feedbackMode == FeedbackMode.ALL
            ) {
                profile.audioResourceId?.let { resourceId ->
                    coroutineScope.launch(Dispatchers.IO) {
                        audioHandler.playAudio(resourceId)
                    }
                } ?: run {
                    Log.w(TAG, "Audio resource ID is null, skipping audio feedback")
                }
            }
            
            Log.d(TAG, "Feedback execution completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute feedback", e)
        }
    }
    
    /**
     * 현재 기기 상태를 기반으로 피드백 모드를 결정합니다.
     * 변경 사항: 휴대폰 시스템 설정을 최우선으로 따릅니다.
     */
    suspend fun determineFeedbackMode(): FeedbackMode {
        return try {
            val isSilent = isSilentMode() // 무음 또는 진동 모드인지 확인
            
            val mode = when {
                // 1. 휴대폰이 '소리 모드'라면 -> 이어폰 여부 상관없이 무조건 오디오 재생 (스피커/이어폰)
                !isSilent -> FeedbackMode.ALL
                
                // 2. 휴대폰이 '무음/진동 모드'라면 -> 오디오 끄고 텍스트+진동
                else -> FeedbackMode.TEXT_VIBRATION
            }
            
            Log.d(TAG, "Feedback mode determined: mode=$mode, silent=$isSilent")
            mode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine feedback mode", e)
            FeedbackMode.TEXT // 에러 시 기본값
        }
    }
    
    /**
     * 기기가 무음 모드인지 확인합니다.
     * 
     * @return 무음 모드 여부
     */
    private fun isSilentMode(): Boolean {
        return try {
            when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT,
                AudioManager.RINGER_MODE_VIBRATE -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check silent mode", e)
            false
        }
    }
    
    /**
     * 모든 피드백을 즉시 정지하고 리소스를 해제합니다.
     * onProceed(), onCancel(), dismiss()에서 반드시 호출해야 합니다.
     */
    fun stopAll() {
        try {
            Log.d(TAG, "Stopping all feedback")
            hapticHandler.stop()
            audioHandler.stop()
            coroutineScope.coroutineContext.cancelChildren()
            Log.d(TAG, "All feedback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop all feedback", e)
        }
    }
    
    /**
     * 리소스를 정리합니다.
     */
    fun cleanup() {
        stopAll()
        coroutineScope.cancel()
    }
}
