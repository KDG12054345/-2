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
     * 등록되지 않은 경우 모든 페르소나의 프롬프트 텍스트 중 랜덤으로 선택합니다.
     * 
     * @return PersonaProfile
     */
    fun getPersonaProfile(): PersonaProfile {
        val personaType = getPersonaType()
        return if (personaType == null) {
            createRandomProfile()
        } else {
            when (personaType) {
                PersonaType.STREET -> createStreetProfile()
                PersonaType.CALM -> createCalmProfile()
                PersonaType.DIPLOMATIC -> createDiplomaticProfile()
                PersonaType.COMFORTABLE -> createComfortableProfile()
            }
        }
    }
    
    /**
     * PreferenceManager에서 페르소나 타입을 읽어옵니다.
     * 
     * @return PersonaType (등록되지 않은 경우 null)
     */
    fun getPersonaType(): PersonaType? {
        val typeName = preferenceManager.getPersonaTypeString()
        return try {
            if (typeName.isEmpty()) {
                null
            } else {
                PersonaType.valueOf(typeName)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid persona type: $typeName", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get persona type", e)
            null
        }
    }
    
    private fun createStreetProfile(): PersonaProfile {
        // STREET 페르소나: 2개의 프롬프트와 오디오 중 랜덤 선택
        val options = listOf(
            Pair(
                context.getString(R.string.persona_prompt_street_1),
                R.raw.homie_bag_street
            ),
            Pair(
                context.getString(R.string.persona_prompt_street_2),
                R.raw.no_cap_street
            )
        )
        
        val selected = options.random()
        return PersonaProfile(
            promptText = selected.first,
            vibrationPattern = listOf(100, 50, 200, 50, 150),
            audioResourceId = selected.second
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
    
    private fun createComfortableProfile(): PersonaProfile {
        // COMFORTABLE 페르소나: 2개의 프롬프트와 오디오 중 랜덤 선택
        val options = listOf(
            Pair(
                context.getString(R.string.persona_prompt_comfortable_1_kim),
                R.raw.korea_rosa_1
            ),
            Pair(
                context.getString(R.string.persona_prompt_comfortable_2_kim),
                R.raw.korea_rosa_2
            )
        )
        
        val selected = options.random()
        return PersonaProfile(
            promptText = selected.first,
            vibrationPattern = listOf(250, 200, 250),
            audioResourceId = selected.second
        )
    }
    
    /**
     * 등록되지 않은 경우 모든 페르소나의 프롬프트 텍스트 중 랜덤으로 선택합니다.
     * 
     * @return PersonaProfile (랜덤 프롬프트 텍스트)
     */
    private fun createRandomProfile(): PersonaProfile {
        // 모든 페르소나의 프롬프트 텍스트 수집
        val allPrompts = mutableListOf<String>()
        
        // STREET 페르소나 프롬프트
        allPrompts.add(context.getString(R.string.persona_prompt_street_1))
        allPrompts.add(context.getString(R.string.persona_prompt_street_2))
        
        // CALM 페르소나 프롬프트
        allPrompts.add(context.getString(R.string.persona_prompt_calm))
        
        // DIPLOMATIC 페르소나 프롬프트
        allPrompts.add(context.getString(R.string.persona_prompt_diplomatic))
        
        // COMFORTABLE 페르소나 프롬프트
        allPrompts.add(context.getString(R.string.persona_prompt_comfortable_1_kim))
        allPrompts.add(context.getString(R.string.persona_prompt_comfortable_2_kim))
        
        // 랜덤으로 하나 선택
        val selectedPrompt = allPrompts.random()
        
        return PersonaProfile(
            promptText = selectedPrompt,
            vibrationPattern = listOf(200, 200, 200), // 기본 진동 패턴
            audioResourceId = null // 오디오 없음
        )
    }
}
