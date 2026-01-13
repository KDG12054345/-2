package com.faust.models

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TransactionType {
    MINING,      // 포인트 채굴
    PENALTY,     // 페널티 차감
    PURCHASE,    // 구매 (MVP 제외)
    RESET        // 주간 정산
}

@Entity(tableName = "point_transactions")
data class PointTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amount: Int,
    val type: TransactionType,
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String = ""
)
