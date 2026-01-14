package com.faust.data.database

import androidx.room.*
import com.faust.models.PointTransaction
import com.faust.models.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface PointTransactionDao {
    @Query("SELECT * FROM point_transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<PointTransaction>>

    @Query("SELECT * FROM point_transactions WHERE type = :type ORDER BY timestamp DESC")
    fun getTransactionsByType(type: TransactionType): Flow<List<PointTransaction>>

    @Query("SELECT * FROM point_transactions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransactions(limit: Int): Flow<List<PointTransaction>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: PointTransaction): Long

    @Query("SELECT SUM(amount) FROM point_transactions")
    suspend fun getTotalPoints(): Int?

    @Query("SELECT COALESCE(SUM(amount), 0) FROM point_transactions")
    fun getTotalPointsFlow(): Flow<Int>

    @Query("DELETE FROM point_transactions WHERE type = :type")
    suspend fun deleteTransactionsByType(type: TransactionType)
}
