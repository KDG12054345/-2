package com.faust.domain.persona.handlers

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * 촉각 피드백 핸들러 인터페이스
 * 페르소나별 진동 패턴을 무한 반복으로 실행합니다.
 */
interface HapticHandler {
    /**
     * 진동 패턴을 무한 반복으로 시작합니다.
     * 
     * @param pattern 진동 패턴 (밀리초 단위: [진동시간, 대기시간, 진동시간, ...])
     */
    suspend fun startVibrationLoop(pattern: List<Long>)
    
    /**
     * 진동을 즉시 정지합니다.
     */
    fun stop()
}

/**
 * HapticHandler 구현
 */
class HapticHandlerImpl(
    private val context: Context
) : HapticHandler {
    companion object {
        private const val TAG = "HapticHandler"
    }
    
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    
    private var vibrationJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    override suspend fun startVibrationLoop(pattern: List<Long>) {
        try {
            stop() // 기존 진동 정지
            
            if (vibrator == null) {
                Log.w(TAG, "Vibrator not available")
                return
            }
            
            if (pattern.isEmpty() || pattern.size % 2 != 0) {
                Log.w(TAG, "Invalid vibration pattern: $pattern")
                return
            }
            
            vibrationJob = coroutineScope.launch {
                while (isActive) {
                    pattern.forEachIndexed { index, duration ->
                        if (!isActive) return@forEachIndexed
                        
                        if (index % 2 == 0) {
                            // 진동 실행
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val effect = VibrationEffect.createOneShot(
                                        duration,
                                        VibrationEffect.DEFAULT_AMPLITUDE
                                    )
                                    vibrator?.vibrate(effect)
                                } else {
                                    @Suppress("DEPRECATION")
                                    vibrator?.vibrate(duration)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to vibrate", e)
                            }
                        } else {
                            // 대기
                            delay(duration)
                        }
                    }
                }
            }
            
            Log.d(TAG, "Vibration loop started with pattern: $pattern")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration loop", e)
        }
    }
    
    override fun stop() {
        try {
            vibrationJob?.cancel()
            vibrationJob = null
            
            vibrator?.cancel()
            
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop vibration", e)
        }
    }
}
