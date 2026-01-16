package com.faust.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.models.BlockedApp
import com.faust.models.UserTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * MainActivity의 데이터 관찰 및 비즈니스 로직을 담당하는 ViewModel입니다.
 * 
 * 역할:
 * - 포인트 합계 관찰 및 StateFlow로 노출
 * - 차단 앱 목록 관찰 및 StateFlow로 노출
 * - 차단 앱 추가/제거 로직 처리
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database: FaustDatabase = (application as FaustApplication).database
    private val preferenceManager: PreferenceManager = PreferenceManager(application)

    // 포인트 합계 StateFlow
    private val _currentPoints = MutableStateFlow<Int>(0)
    val currentPoints: StateFlow<Int> = _currentPoints.asStateFlow()

    // 차단 앱 목록 StateFlow
    private val _blockedApps = MutableStateFlow<List<BlockedApp>>(emptyList())
    val blockedApps: StateFlow<List<BlockedApp>> = _blockedApps.asStateFlow()

    init {
        observePoints()
        observeBlockedApps()
    }

    /**
     * [핵심 이벤트: 데이터 동기화 이벤트 - getTotalPointsFlow]
     * 
     * 역할: 데이터베이스의 포인트 합계가 변경되면 ViewModel의 StateFlow를 자동으로 갱신합니다.
     * 트리거: 데이터베이스의 포인트 합계가 변경될 때 자동 발생
     * 처리: PointTransactionDao.getTotalPointsFlow()를 구독하여 포인트 변경 시 StateFlow 업데이트
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun observePoints() {
        viewModelScope.launch {
            database.pointTransactionDao().getTotalPointsFlow()
                .catch { e ->
                    // 에러 발생 시 0으로 설정
                    _currentPoints.value = 0
                }
                .collect { points ->
                    // 포인트는 항상 0 이상으로 보장
                    _currentPoints.value = points.coerceAtLeast(0)
                }
        }
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
