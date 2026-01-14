package com.faust.domain

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.models.TransactionType
import com.faust.models.UserTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [핵심 이벤트: 포인트 및 페널티 이벤트 처리]
 * 
 * 역할: 강행 실행 및 철회 시 페널티를 계산하고 적용하는 서비스입니다.
 * 트리거: GuiltyNegotiationOverlay.onProceed() 또는 onCancel() 호출
 * 처리: 사용자 티어에 따라 페널티 계산, DB 트랜잭션으로 포인트 차감 및 거래 내역 저장 (즉시 완료 대기)
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

    companion object {
        private const val TAG = "PenaltyService"
        private const val LAUNCH_PENALTY = 6 // 모든 티어: 강행 실행 시 6 WP 차감
        private const val FREE_TIER_QUIT_PENALTY = 3 // Free 티어: 철회 시 3 WP 차감
        private const val STANDARD_TIER_QUIT_PENALTY = 3 // Standard 티어: 철회 시 3 WP 차감
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - onProceed 처리]
     * 
     * 역할: 앱 강행 실행 시 페널티를 적용합니다. 모든 티어는 6 WP를 차감합니다.
     * 트리거: GuiltyNegotiationOverlay.onProceed() 호출
     * 처리: 사용자 티어에 따라 페널티 계산, applyPenalty() 호출 (즉시 완료 대기)
     */
    suspend fun applyLaunchPenalty(packageName: String, appName: String) {
        withContext(Dispatchers.IO) {
            val userTier = preferenceManager.getUserTier()
            val penalty = when (userTier) {
                UserTier.FREE -> LAUNCH_PENALTY
                UserTier.STANDARD -> LAUNCH_PENALTY
                UserTier.FAUST_PRO -> LAUNCH_PENALTY // 추후 변경 예정
            }

            applyPenalty(penalty, "앱 강행 실행: $appName")
        }
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - onCancel 처리]
     * 
     * 역할: 앱 철회 시 페널티를 적용합니다. Free 티어는 3 WP, Standard 티어는 3 WP를 차감합니다.
     * 트리거: GuiltyNegotiationOverlay.onCancel() 호출
     * 처리: 사용자 티어에 따라 페널티 계산, applyPenalty() 호출 (페널티 > 0인 경우만, 즉시 완료 대기)
     */
    suspend fun applyQuitPenalty(packageName: String, appName: String) {
        withContext(Dispatchers.IO) {
            val userTier = preferenceManager.getUserTier()
            val penalty = when (userTier) {
                UserTier.FREE -> FREE_TIER_QUIT_PENALTY
                UserTier.STANDARD -> STANDARD_TIER_QUIT_PENALTY
                UserTier.FAUST_PRO -> 0 // 추후 변경 예정
            }

            if (penalty > 0) {
                Log.w(TAG, "철회 버튼 클릭: ${penalty} WP 차감 예정 (티어: ${userTier.name})")
                applyPenalty(penalty, "앱 철회: $appName")
            } else {
                Log.d(TAG, "철회 버튼 클릭: 포인트 차감 없음 (티어: ${userTier.name})")
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
                        val newPoints = (currentPoints - actualPenalty).coerceAtLeast(0)
                        preferenceManager.setCurrentPoints(newPoints)
                        
                        Log.w(TAG, "포인트 차감 완료: ${actualPenalty} WP 차감 (기존: ${currentPoints} WP → 현재: ${newPoints} WP), 사유: $reason")
                    } else {
                        Log.w(TAG, "포인트 차감 실패: 현재 포인트(${currentPoints} WP)가 부족하여 차감 불가, 요청된 페널티: ${penalty} WP")
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
