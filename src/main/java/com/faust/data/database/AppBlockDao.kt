package com.faust.data.database

import androidx.room.*
import com.faust.models.BlockedApp
import kotlinx.coroutines.flow.Flow

@Dao
interface AppBlockDao {
    @Query("SELECT * FROM blocked_apps ORDER BY blockedAt DESC")
    fun getAllBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getBlockedApp(packageName: String): BlockedApp?

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName LIMIT 1")
    fun getBlockedAppFlow(packageName: String): Flow<BlockedApp?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApp(app: BlockedApp)

    @Delete
    suspend fun deleteBlockedApp(app: BlockedApp)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun deleteBlockedAppByPackage(packageName: String)

    @Query("SELECT COUNT(*) FROM blocked_apps")
    suspend fun getBlockedAppCount(): Int
}
