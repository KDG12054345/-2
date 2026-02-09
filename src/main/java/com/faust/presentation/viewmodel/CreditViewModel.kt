package com.faust.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.faust.data.utils.PreferenceManager
import com.faust.models.UserCreditType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CreditFragment의 데이터 관찰 및 비즈니스 로직을 담당하는 ViewModel입니다.
 * FreePass 시스템을 대체하는 TimeCredit 시스템의 UI 상태를 관리합니다.
 */
class CreditViewModel(application: Application) : AndroidViewModel(application) {
    private val preferenceManager: PreferenceManager = PreferenceManager(application)

    // 크레딧 잔액 (초 단위)
    private val _creditBalance = MutableStateFlow(0L)
    val creditBalance: StateFlow<Long> = _creditBalance.asStateFlow()

    // 사용자 크레딧 타입
    private val _userCreditType = MutableStateFlow(UserCreditType.PRO)
    val userCreditType: StateFlow<UserCreditType> = _userCreditType.asStateFlow()

    // 최대 누적 시간
    private val _maxCap = MutableStateFlow(120)
    val maxCap: StateFlow<Int> = _maxCap.asStateFlow()

    // 누적 절제 시간 (초 단위)
    private val _accumulatedAbstention = MutableStateFlow(0L)
    val accumulatedAbstention: StateFlow<Long> = _accumulatedAbstention.asStateFlow()

    init {
        loadInitialState()
        startPeriodicUpdate()
    }

    /**
     * 초기 상태를 로드합니다.
     */
    private fun loadInitialState() {
        _creditBalance.value = preferenceManager.getTimeCreditBalanceSeconds()
        _userCreditType.value = preferenceManager.getTimeCreditUserType()
        _maxCap.value = preferenceManager.getTimeCreditMaxCap()
        _accumulatedAbstention.value = preferenceManager.getAccumulatedAbstentionSeconds()
    }

    /**
     * 주기적으로 상태를 업데이트합니다 (1초마다).
     * 쿨다운 타이머 및 잔액 갱신용.
     */
    private fun startPeriodicUpdate() {
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                refreshState()
            }
        }
    }

    /**
     * 현재 상태를 갱신합니다.
     */
    private fun refreshState() {
        _creditBalance.value = preferenceManager.getTimeCreditBalanceSeconds()
        _accumulatedAbstention.value = preferenceManager.getAccumulatedAbstentionSeconds()
    }

    /**
     * 사용자 크레딧 타입을 변경합니다.
     */
    fun setUserCreditType(type: UserCreditType) {
        preferenceManager.setTimeCreditUserType(type)
        _userCreditType.value = type
    }

    /**
     * [테스트 전용] 타임 크레딧(보상 시간)을 1분(60초) 증가시킵니다.
     * 클릭 시 잔액에 60초를 더하고 즉시 반영·저장합니다.
     */
    fun addTestCreditOneMinute() {
        val current = preferenceManager.getTimeCreditBalanceSeconds()
        val newBalance = current + 60L
        preferenceManager.setTimeCreditBalanceSeconds(newBalance)
        preferenceManager.persistTimeCreditValues()
        _creditBalance.value = newBalance
    }
}
