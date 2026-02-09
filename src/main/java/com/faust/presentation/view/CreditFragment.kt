package com.faust.presentation.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.faust.R
import com.faust.models.UserCreditType
import com.faust.presentation.viewmodel.CreditViewModel
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 타임크레딧 Fragment
 * 보상 시간 잔액 확인, 사용자 타입 선택을 표시합니다.
 */
class CreditFragment : Fragment() {
    private val viewModel: CreditViewModel by viewModels()

    private lateinit var textCreditBalance: TextView
    private lateinit var textCreditStatus: TextView
    private lateinit var cardCooldown: MaterialCardView
    private lateinit var textCooldownRemaining: TextView
    private lateinit var toggleGroupCreditType: MaterialButtonToggleGroup
    private lateinit var textProfileDescription: TextView
    private lateinit var textMaxCap: TextView
    private lateinit var textAccumulatedAbstention: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_credit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textCreditBalance = view.findViewById(R.id.textCreditBalance)
        textCreditStatus = view.findViewById(R.id.textCreditStatus)
        cardCooldown = view.findViewById(R.id.cardCooldown)
        textCooldownRemaining = view.findViewById(R.id.textCooldownRemaining)
        cardCooldown.visibility = View.GONE // 타임 크레딧 10분 쿨타임 기능 제거
        toggleGroupCreditType = view.findViewById(R.id.toggleGroupCreditType)
        textProfileDescription = view.findViewById(R.id.textProfileDescription)
        textMaxCap = view.findViewById(R.id.textMaxCap)
        textAccumulatedAbstention = view.findViewById(R.id.textAccumulatedAbstention)

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonTestAddOneMinute)
            .setOnClickListener { viewModel.addTestCreditOneMinute() }

        setupToggleGroup()
        observeViewModel()
    }

    private fun setupToggleGroup() {
        toggleGroupCreditType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val selectedType = when (checkedId) {
                    R.id.buttonLight -> UserCreditType.LIGHT
                    R.id.buttonPro -> UserCreditType.PRO
                    R.id.buttonDetox -> UserCreditType.DETOX
                    else -> return@addOnButtonCheckedListener
                }
                viewModel.setUserCreditType(selectedType)
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.creditBalance.collect { balanceSeconds ->
                textCreditBalance.text = formatSecondsAsHhMmSs(balanceSeconds)
                textCreditStatus.text = if (balanceSeconds > 0L) {
                    "차단 앱 실행 시 자동으로 사용됩니다"
                } else {
                    "절제를 통해 보상을 쌓아보세요"
                }
            }
        }

        lifecycleScope.launch {
            viewModel.userCreditType.collect { type ->
                // Toggle 버튼 상태 동기화
                val buttonId = when (type) {
                    UserCreditType.LIGHT -> R.id.buttonLight
                    UserCreditType.PRO -> R.id.buttonPro
                    UserCreditType.DETOX -> R.id.buttonDetox
                }
                if (toggleGroupCreditType.checkedButtonId != buttonId) {
                    toggleGroupCreditType.check(buttonId)
                }
                textProfileDescription.text = "${type.ratio}분 절제 = 1분 크레딧"
            }
        }

        lifecycleScope.launch {
            viewModel.maxCap.collect { cap ->
                textMaxCap.text = "${cap}분"
            }
        }

        lifecycleScope.launch {
            viewModel.accumulatedAbstention.collect { seconds ->
                textAccumulatedAbstention.text = formatSecondsAsHhMmSs(seconds)
            }
        }
    }

    private fun formatSecondsAsHhMmSs(totalSeconds: Long): String {
        val s = (totalSeconds % 60).toInt()
        val m = ((totalSeconds / 60) % 60).toInt()
        val h = (totalSeconds / 3600).toInt()
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
