package com.faust.domain.persona

import android.util.Log
import com.faust.data.utils.PreferenceManager

/**
 * 페르소나 프로필 제공자
 * PreferenceManager에서 사용자가 선택한 페르소나 타입을 읽어와
 * 해당하는 PersonaProfile을 제공합니다.
 */
class PersonaProvider(
    private val preferenceManager: PreferenceManager
) {
    companion object {
        private const val TAG = "PersonaProvider"
        private const val KEY_PERSONA_TYPE = "persona_type"
    }
    
    /**
     * 현재 설정된 페르소나 프로필을 반환합니다.
     * 
     * @return PersonaProfile (기본값: CALM)
     */
    fun getPersonaProfile(): PersonaProfile {
        val personaType = getPersonaType()
        return when (personaType) {
            PersonaType.RHYTHMICAL -> createRhythmicalProfile()
            PersonaType.CALM -> createCalmProfile()
            PersonaType.DIPLOMATIC -> createDiplomaticProfile()
        }
    }
    
    /**
     * PreferenceManager에서 페르소나 타입을 읽어옵니다.
     * 
     * @return PersonaType (기본값: CALM)
     */
    fun getPersonaType(): PersonaType {
        return try {
            val typeName = preferenceManager.getPersonaTypeString()
            PersonaType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid persona type, defaulting to CALM", e)
            PersonaType.CALM
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get persona type", e)
            PersonaType.CALM
        }
    }
    
    private fun createRhythmicalProfile(): PersonaProfile {
        return PersonaProfile(
            promptText = "나는 지금 시간을 낭비하고 있습니다",
            vibrationPattern = listOf(100, 50, 200, 50, 150),
            audioResourceId = null // TODO: R.raw.persona_rhythmical 추가 시 사용
        )
    }
    
    private fun createCalmProfile(): PersonaProfile {
        return PersonaProfile(
            promptText = "잠깐, 이게 정말 필요한 행동인가요?",
            vibrationPattern = listOf(200, 300, 200),
            audioResourceId = null // TODO: R.raw.persona_calm 추가 시 사용
        )
    }
    
    private fun createDiplomaticProfile(): PersonaProfile {
        return PersonaProfile(
            promptText = "다시 한 번 생각해보세요",
            vibrationPattern = listOf(150, 100, 150, 100, 150),
            audioResourceId = null // TODO: R.raw.persona_diplomatic 추가 시 사용
        )
    }
}
