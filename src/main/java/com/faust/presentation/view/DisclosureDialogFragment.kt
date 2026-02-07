package com.faust.presentation.view

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.faust.R

/**
 * 접근성 서비스 및 기기 관리자 권한 요청 전 명시적 고지 및 동의 다이얼로그
 * 
 * 역할: 구글 플레이 정책 준수를 위해 접근성 API 사용 목적과 데이터 수집 내용을 명시적으로 고지하고 사용자 동의를 받습니다.
 * 트리거: MainFragment에서 엄격모드 활성화 버튼 클릭 시
 * 처리: 사용 목적, 데이터 수집 내용 표시, 명시적 동의 버튼 제공
 */
class DisclosureDialogFragment(
    private val onConsent: () -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_disclosure, null)

        val textPurpose = view.findViewById<TextView>(R.id.textPurpose)
        val textDataCollection = view.findViewById<TextView>(R.id.textDataCollection)
        val buttonConsent = view.findViewById<Button>(R.id.buttonConsent)
        val buttonCancel = view.findViewById<Button>(R.id.buttonCancel)

        // 고지 내용 설정
        textPurpose?.text = getString(R.string.disclosure_purpose)
        textDataCollection?.text = getString(R.string.disclosure_data_collection)

        // 동의 버튼 클릭 시
        buttonConsent?.setOnClickListener {
            onConsent()
            dismiss()
        }

        // 취소 버튼 클릭 시
        buttonCancel?.setOnClickListener {
            dismiss()
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.disclosure_title))
            .setView(view)
            .setCancelable(false)
            .create()
    }
}
