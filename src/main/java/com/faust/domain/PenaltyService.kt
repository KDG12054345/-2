package com.faust.domain

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.models.TransactionType
import com.faust.models.UserTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [핵심 이벤트: 포인트 및 페널티 이벤트 처리]
 * 
 * 역할: 강행 실행 및 철회 시 페널티를 계산하고 적용하는 서비스입니다.
 * 트리거: GuiltyNegotiationOverlay.onProceed() 또는 onCancel() 호출
 * 처리: 사용자 티어에 따라 페널티 계산, DB 트랜잭션으로 포인트 차감 및 거래 내역 저장
 * 
 * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
 */
class PenaltyService(private val context: Context) {
    private val database: FaustDatabase by lazy {
        (context.applicationContext as FaustApplication).database
    }
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(context)
    }
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "PenaltyService"
        private const val FREE_TIER_LAUNCH_PENALTY = 3
        private const val FREE_TIER_QUIT_PENALTY = 0 // Free 티어는 철회 시 차감 없음
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - onProceed 처리]
     * 
     * 역할: 앱 강행 실행 시 페널티를 적용합니다. Free 티어는 3 WP를 차감합니다.
     * 트리거: GuiltyNegotiationOverlay.onProceed() 호출
     * 처리: 사용자 티어에 따라 페널티 계산, applyPenalty() 호출
     */
    fun applyLaunchPenalty(packageName: String, appName: String) {
        scope.launch {
            val userTier = preferenceManager.getUserTier()
            val penalty = when (userTier) {
                UserTier.FREE -> FREE_TIER_LAUNCH_PENALTY
                UserTier.STANDARD -> FREE_TIER_LAUNCH_PENALTY // MVP에서는 동일
                UserTier.FAUST_PRO -> FREE_TIER_LAUNCH_PENALTY // MVP에서는 동일
            }

            applyPenalty(penalty, "앱 강행 실행: $appName")
        }
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - onCancel 처리]
     * 
     * 역할: 앱 철회 시 페널티를 적용합니다. Free 티어는 페널티 0입니다.
     * 트리거: GuiltyNegotiationOverlay.onCancel() 호출
     * 처리: 사용자 티어에 따라 페널티 계산 (Free 티어는 0), applyPenalty() 호출 (페널티 > 0인 경우만)
     */
    fun applyQuitPenalty(packageName: String, appName: String) {
        scope.launch {
            val userTier = preferenceManager.getUserTier()
            val penalty = when (userTier) {
                UserTier.FREE -> FREE_TIER_QUIT_PENALTY
                UserTier.STANDARD -> 2 // MVP 이후 구현
                UserTier.FAUST_PRO -> 0 // MVP 이후 구현 (실제로는 50% 삭감)
            }

            if (penalty > 0) {
                applyPenalty(penalty, "앱 철회: $appName")
            }
        }
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - 페널티 적용]
     * 
     * 역할: 페널티를 적용합니다. DB 트랜잭션으로 포인트 차감과 거래 내역 저장을 원자적으로 처리합니다.
     * 트리거: applyLaunchPenalty() 또는 applyQuitPenalty() 호출
     * 처리: 현재 포인트 조회, 페널티 계산 및 차감, 거래 내역 저장, PreferenceManager 동기화 (트랜잭션 보장)
     */
    private suspend fun applyPenalty(penalty: Int, reason: String) {
        if (penalty <= 0) return

        try {
            database.withTransaction {
                try {
                    // 현재 포인트 조회 (DB에서 계산)
                    val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0
                    val actualPenalty = penalty.coerceAtMost(currentPoints)

                    if (actualPenalty > 0) {
                        // 거래 내역 저장 (트랜잭션으로 원자적 처리)
                        database.pointTransactionDao().insertTransaction(
                            com.faust.models.PointTransaction(
                                amount = -actualPenalty,
                                type = TransactionType.PENALTY,
                                reason = reason
                            )
                        )
                        
                        // PreferenceManager 동기화 (호환성 유지)
                        preferenceManager.setCurrentPoints((currentPoints - actualPenalty).coerceAtLeast(0))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying penalty in transaction: penalty=$penalty, reason=$reason", e)
                    throw e // 트랜잭션 롤백을 위해 예외 재발생
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply penalty: penalty=$penalty, reason=$reason", e)
            // 트랜잭션이 실패하면 자동으로 롤백됨
        }
    }
}
