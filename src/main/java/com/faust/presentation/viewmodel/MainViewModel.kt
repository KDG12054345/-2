package com.faust.presentation.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.domain.TimeCreditService
import com.faust.models.BlockedApp
import com.faust.models.UserTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 메인 화면 타이머 UI 상태. Idle=비세션, Running=카운트다운, Syncing=00:00 고정 후 잔액 갱신 대기.
 * 잔액은 초 단위로 유지 (second-precision).
 */
sealed class TimerUiState {
    data class Idle(val balanceSeconds: Long) : TimerUiState()
    data class Running(val remainingSeconds: Long) : TimerUiState()
    data object Syncing : TimerUiState()
}

/**
 * MainActivity의 데이터 관찰 및 비즈니스 로직을 담당하는 ViewModel입니다.
 *
 * 역할:
 * - TimeCredit 잔액 기반 타이머 상태(TimerUiState) 노출 (메인 카드 표시용)
 * - 차단 앱 목록 관찰 및 StateFlow로 노출
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database: FaustDatabase = (application as FaustApplication).database
    private val preferenceManager: PreferenceManager = PreferenceManager(application)

    // TimeCredit 타이머 UI 상태 (메인 카드용)
    private val _timerUiState = MutableStateFlow<TimerUiState>(TimerUiState.Idle(0))
    val timerUiState: StateFlow<TimerUiState> = _timerUiState.asStateFlow()

    // 차단 앱 목록 StateFlow
    private val _blockedApps = MutableStateFlow<List<BlockedApp>>(emptyList())
    val blockedApps: StateFlow<List<BlockedApp>> = _blockedApps.asStateFlow()

    init {
        observeBlockedApps()
        observeTimeCreditTimer()
    }

    private fun observeTimeCreditTimer() {
        viewModelScope.launch {
            preferenceManager.getTimeCreditBalanceFlow()
                .map { balanceSeconds -> computeTimerState(balanceSeconds) }
                .distinctUntilChanged()
                .catch { e -> _timerUiState.value = TimerUiState.Idle(preferenceManager.getTimeCreditBalanceSeconds()) }
                .collect { state ->
                    _timerUiState.value = state
                    if (state is TimerUiState.Syncing) {
                        launchSyncSafeguard()
                    }
                }
        }
    }

    private fun computeTimerState(balanceSeconds: Long): TimerUiState {
        val sessionActive = preferenceManager.isCreditSessionActive()
        if (!sessionActive) return TimerUiState.Idle(balanceSeconds)
        val startElapsed = TimeCreditService.sessionStartElapsedRealtime
        val remainingSeconds = getRemainingSecondsForDisplay(balanceSeconds, startElapsed)
        return when {
            remainingSeconds > 0L -> TimerUiState.Running(remainingSeconds)
            balanceSeconds > 0L -> TimerUiState.Running(balanceSeconds)
            else -> TimerUiState.Syncing
        }
    }

    /**
     * 세션 중 남은 초. max(0, balanceSeconds - elapsedSec). Doze 등 주기 오차는 매 호출 시 차이값 재계산으로 보정.
     */
    fun getRemainingSecondsForDisplay(balanceSeconds: Long, sessionStartElapsed: Long): Long {
        if (sessionStartElapsed <= 0L) return balanceSeconds.coerceAtLeast(0L)
        val elapsedSec = (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000L
        return (balanceSeconds - elapsedSec).coerceAtLeast(0L)
    }

    /** 현재 잔액(초)·세션 시작 단조 시간으로 남은 초 계산 (Fragment 1초 틱에서 호출). */
    fun getRemainingSecondsForDisplay(): Long {
        val balanceSeconds = preferenceManager.getTimeCreditBalanceSeconds()
        val startElapsed = TimeCreditService.sessionStartElapsedRealtime
        return getRemainingSecondsForDisplay(balanceSeconds, startElapsed)
    }

    private var syncSafeguardJob: kotlinx.coroutines.Job? = null

    private fun launchSyncSafeguard() {
        syncSafeguardJob?.cancel()
        syncSafeguardJob = viewModelScope.launch {
            delay(5000L)
            if (_timerUiState.value is TimerUiState.Syncing) {
                TimeCreditService(getApplication()).endCreditSession()
            }
            syncSafeguardJob = null
        }
    }

    /** HH:MM:SS 포맷 (Locale.US로 숫자 일관). */
    fun formatTimeAsHhMmSs(totalSeconds: Long): String {
        val s = (totalSeconds % 60).toInt()
        val m = ((totalSeconds / 60) % 60).toInt()
        val h = (totalSeconds / 3600).toInt()
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** 비세션 시 잔액(초)을 HH:MM:SS 문자열로. */
    fun formatBalanceAsHhMmSs(balanceSeconds: Long): String {
        return formatTimeAsHhMmSs(balanceSeconds.coerceAtLeast(0L))
    }

    /**
     * 차단 앱 목록을 관찰하고 StateFlow로 노출합니다.
     */
    private fun observeBlockedApps() {
        viewModelScope.launch {
            database.appBlockDao().getAllBlockedApps()
                .catch { e ->
                    // 에러 발생 시 빈 리스트로 설정
                    _blockedApps.value = emptyList()
                }
                .collect { apps ->
                    _blockedApps.value = apps
                }
        }
    }

    /**
     * 차단 앱을 추가합니다.
     * @param app 추가할 차단 앱
     * @return 성공 여부
     */
    suspend fun addBlockedApp(app: BlockedApp): Boolean {
        return try {
            // 테스트 모드 확인
            val testModeMax = preferenceManager.getTestModeMaxApps()
            val maxApps = if (testModeMax != null) {
                testModeMax
            } else {
                // 티어별 최대 앱 수 확인
                val userTier = preferenceManager.getUserTier()
                when (userTier) {
                    UserTier.FREE -> 1
                    UserTier.STANDARD -> 3
                    UserTier.FAUST_PRO -> Int.MAX_VALUE
                }
            }

            val currentCount = database.appBlockDao().getBlockedAppCount()
            if (currentCount >= maxApps) {
                false // 최대 개수 초과
            } else {
                database.appBlockDao().insertBlockedApp(app)
                true // 성공
            }
        } catch (e: Exception) {
            false // 실패
        }
    }

    /**
     * 차단 앱을 제거합니다.
     * @param app 제거할 차단 앱
     */
    suspend fun removeBlockedApp(app: BlockedApp) {
        try {
            database.appBlockDao().deleteBlockedApp(app)
        } catch (e: Exception) {
            // 에러는 무시 (이미 제거되었을 수 있음)
        }
    }

    /**
     * 현재 사용자 티어에 따른 최대 차단 앱 개수를 반환합니다.
     */
    fun getMaxBlockedApps(): Int {
        // 테스트 모드 확인
        val testModeMax = preferenceManager.getTestModeMaxApps()
        return if (testModeMax != null) {
            testModeMax
        } else {
            val userTier = preferenceManager.getUserTier()
            when (userTier) {
                UserTier.FREE -> 1
                UserTier.STANDARD -> 3
                UserTier.FAUST_PRO -> Int.MAX_VALUE
            }
        }
    }

    /**
     * 현재 차단 앱 개수를 반환합니다.
     */
    suspend fun getCurrentBlockedAppCount(): Int {
        return try {
            database.appBlockDao().getBlockedAppCount()
        } catch (e: Exception) {
            0
        }
    }
}
