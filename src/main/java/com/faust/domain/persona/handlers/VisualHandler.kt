package com.faust.domain.persona.handlers

import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * 시각 피드백 핸들러 인터페이스
 * 페르소나가 제시하는 문구를 표시하고 사용자 입력을 검증합니다.
 */
interface VisualHandler {
    /**
     * 페르소나 문구를 화면에 표시합니다.
     * 
     * @param promptText 표시할 문구
     * @param textView 문구를 표시할 TextView
     * @param editText 사용자 입력을 받을 EditText
     * @param proceedButton 입력 검증에 따라 활성화/비활성화될 버튼
     */
    fun displayPrompt(
        promptText: String,
        textView: TextView,
        editText: EditText,
        proceedButton: Button
    )
    
    /**
     * 입력 검증을 설정합니다.
     * EditText에 TextWatcher를 추가하여 실시간으로 입력을 검증합니다.
     * 
     * @param editText 검증할 EditText
     * @param expectedText 기대하는 정확한 문구
     * @param proceedButton 입력이 일치할 때 활성화될 버튼
     */
    fun setupInputValidation(
        editText: EditText,
        expectedText: String,
        proceedButton: Button
    )
    
    /**
     * 입력이 기대하는 문구와 일치하는지 검증합니다.
     * 
     * @param input 사용자 입력
     * @param expectedText 기대하는 문구
     * @return 일치 여부
     */
    fun validateInput(input: String, expectedText: String): Boolean
}

/**
 * VisualHandler 구현
 */
class VisualHandlerImpl : VisualHandler {
    companion object {
        private const val TAG = "VisualHandler"
    }
    
    override fun displayPrompt(
        promptText: String,
        textView: TextView,
        editText: EditText,
        proceedButton: Button
    ) {
        try {
            textView.text = promptText
            editText.text?.clear()
            proceedButton.isEnabled = false
            Log.d(TAG, "Prompt displayed: $promptText")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display prompt", e)
        }
    }
    
    override fun setupInputValidation(
        editText: EditText,
        expectedText: String,
        proceedButton: Button
    ) {
        try {
            // 기존 TextWatcher 제거 (중복 방지)
            editText.removeTextChangedListener(null)
            
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                
                override fun afterTextChanged(s: Editable?) {
                    val input = s?.toString() ?: ""
                    val isMatch = validateInput(input, expectedText)
                    proceedButton.isEnabled = isMatch
                    
                    if (isMatch) {
                        Log.d(TAG, "Input validation passed")
                    }
                }
            })
            
            Log.d(TAG, "Input validation setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup input validation", e)
        }
    }
    
    override fun validateInput(input: String, expectedText: String): Boolean {
        return input.trim() == expectedText.trim()
    }
}
