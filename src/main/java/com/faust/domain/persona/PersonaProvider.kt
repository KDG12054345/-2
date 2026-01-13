package com.faust.domain.persona

import android.content.Context
import android.util.Log
import com.faust.R
import com.faust.data.utils.PreferenceManager

/**
 * 페르소나 프로필 제공자
 * PreferenceManager에서 사용자가 선택한 페르소나 타입을 읽어와
 * 해당하는 PersonaProfile을 제공합니다.
 */
class PersonaProvider(
    private val preferenceManager: PreferenceManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "PersonaProvider"
        private const val KEY_PERSONA_TYPE = "persona_type"
    }
    
    /**
     * 현재 설정된 페르소나 프로필을 반환합니다.
     * 
     * @return PersonaProfile (기본값: RHYTHMICAL)
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
     * @return PersonaType (기본값: RHYTHMICAL)
     */
    fun getPersonaType(): PersonaType {
        return try {
            val typeName = preferenceManager.getPersonaTypeString()
            PersonaType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid persona type, defaulting to RHYTHMICAL", e)
            PersonaType.RHYTHMICAL
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get persona type", e)
            PersonaType.RHYTHMICAL
        }
    }
    
    private fun createRhythmicalProfile(): PersonaProfile {
        return PersonaProfile(
            promptText = context.getString(R.string.persona_prompt_rhythmical),
            vibrationPattern = listOf(100, 50, 200, 50, 150),
            audioResourceId = null // TODO: R.raw.persona_rhythmical 추가 시 사용
        )
    }
    
    private fun createCalmProfile(): PersonaProfile {
        return PersonaProfile(
            promptText = context.getString(R.string.persona_prompt_calm),
            vibrationPattern = listOf(200, 300, 200),
            audioResourceId = null // TODO: R.raw.persona_calm 추가 시 사용
        )
    }
    
    private fun createDiplomaticProfile(): PersonaProfile {
        return PersonaProfile(
            promptText = context.getString(R.string.persona_prompt_diplomatic),
            vibrationPattern = listOf(150, 100, 150, 100, 150),
            audioResourceId = null // TODO: R.raw.persona_diplomatic 추가 시 사용
        )
    }
}
