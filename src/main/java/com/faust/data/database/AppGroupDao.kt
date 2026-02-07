package com.faust.data.database

import androidx.room.*
import com.faust.models.AppGroup
import com.faust.models.AppGroupType
import kotlinx.coroutines.flow.Flow

@Dao
interface AppGroupDao {
    /**
     * 모든 앱 그룹을 Flow로 반환합니다.
     */
    @Query("SELECT * FROM app_groups")
    fun getAllGroups(): Flow<List<AppGroup>>

    /**
     * 특정 그룹 타입의 모든 앱을 Flow로 반환합니다.
     */
    @Query("SELECT * FROM app_groups WHERE groupType = :groupType")
    fun getGroupsByType(groupType: AppGroupType): Flow<List<AppGroup>>

    /**
     * 특정 패키지명이 특정 그룹에 포함되어 있는지 확인합니다.
     */
    @Query("SELECT * FROM app_groups WHERE packageName = :packageName AND groupType = :groupType AND isIncluded = 1")
    suspend fun isAppInGroup(packageName: String, groupType: AppGroupType): AppGroup?

    /**
     * 앱 그룹을 삽입하거나 업데이트합니다.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateGroup(group: AppGroup)

    /**
     * 여러 앱 그룹을 삽입하거나 업데이트합니다.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateGroups(groups: List<AppGroup>)

    /**
     * 앱 그룹을 삭제합니다.
     */
    @Delete
    suspend fun deleteGroup(group: AppGroup)

    /**
     * 특정 그룹 타입의 모든 앱 그룹을 삭제합니다.
     */
    @Query("DELETE FROM app_groups WHERE groupType = :groupType")
    suspend fun deleteGroupsByType(groupType: AppGroupType)

    /**
     * 모든 앱 그룹을 삭제합니다.
     */
    @Query("DELETE FROM app_groups")
    suspend fun deleteAllGroups()
}
