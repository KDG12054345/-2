package com.faust.domain.persona.handlers

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 청각 피드백 핸들러 인터페이스
 * res/raw의 로컬 오디오 파일을 MediaPlayer로 재생합니다.
 */
interface AudioHandler {
    /**
     * 오디오 파일을 재생합니다.
     * 
     * @param resourceId res/raw의 오디오 파일 리소스 ID
     */
    suspend fun playAudio(resourceId: Int)
    
    /**
     * 오디오 재생을 즉시 정지하고 리소스를 해제합니다.
     */
    fun stop()
    
    /**
     * 헤드셋이 연결되어 있는지 확인합니다.
     * 
     * @return 헤드셋 연결 여부
     */
    fun isHeadsetConnected(): Boolean
}

/**
 * AudioHandler 구현
 */
class AudioHandlerImpl(
    private val context: Context
) : AudioHandler {
    companion object {
        private const val TAG = "AudioHandler"
    }
    
    private var mediaPlayer: MediaPlayer? = null
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    override suspend fun playAudio(resourceId: Int) {
        try {
            stop() // 기존 재생 정지
            
            withContext(Dispatchers.IO) {
                mediaPlayer = MediaPlayer.create(context, resourceId)
                
                mediaPlayer?.let { player ->
                    player.setOnCompletionListener {
                        try {
                            it.release()
                            mediaPlayer = null
                            Log.d(TAG, "Audio playback completed")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to release MediaPlayer on completion", e)
                        }
                    }
                    
                    player.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        try {
                            player.release()
                            mediaPlayer = null
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to release MediaPlayer on error", e)
                        }
                        true
                    }
                    
                    player.start()
                    Log.d(TAG, "Audio playback started: resourceId=$resourceId")
                } ?: run {
                    Log.e(TAG, "Failed to create MediaPlayer for resourceId: $resourceId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            stop()
        }
    }
    
    override fun stop() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                mediaPlayer = null
                Log.d(TAG, "Audio playback stopped and released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio", e)
            mediaPlayer = null
        }
    }
    
    override fun isHeadsetConnected(): Boolean {
        return try {
            val isWiredHeadsetOn = audioManager.isWiredHeadsetOn
            val isBluetoothA2dpOn = audioManager.isBluetoothA2dpOn
            
            val connected = isWiredHeadsetOn || isBluetoothA2dpOn
            Log.d(TAG, "Headset connected: $connected (wired=$isWiredHeadsetOn, bluetooth=$isBluetoothA2dpOn)")
            connected
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check headset connection", e)
            false
        }
    }
}
