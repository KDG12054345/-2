package com.faust.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.faust.models.BlockedDomain
import kotlinx.coroutines.flow.Flow

/**
 * 차단된 도메인에 대한 데이터 액세스 객체(DAO)입니다.
 */
@Dao
interface BlockedDomainDao {
    
    /**
     * 모든 차단된 도메인을 차단 시간 역순으로 조회합니다.
     * @return Flow로 감싼 차단 도메인 목록
     */
    @Query("SELECT * FROM blocked_domains ORDER BY blockedAt DESC")
    fun getAllBlockedDomains(): Flow<List<BlockedDomain>>
    
    /**
     * 모든 차단된 도메인을 동기적으로 조회합니다.
     * @return 차단 도메인 목록
     */
    @Query("SELECT * FROM blocked_domains ORDER BY blockedAt DESC")
    suspend fun getAllBlockedDomainsSync(): List<BlockedDomain>
    
    /**
     * 특정 도메인이 차단되었는지 확인합니다.
     * @param domain 확인할 도메인
     * @return 차단된 도메인 정보, 없으면 null
     */
    @Query("SELECT * FROM blocked_domains WHERE domain = :domain LIMIT 1")
    suspend fun getBlockedDomain(domain: String): BlockedDomain?
    
    /**
     * 특정 도메인이 차단되었는지 Flow로 관찰합니다.
     * @param domain 확인할 도메인
     * @return Flow로 감싼 차단 도메인 정보
     */
    @Query("SELECT * FROM blocked_domains WHERE domain = :domain LIMIT 1")
    fun getBlockedDomainFlow(domain: String): Flow<BlockedDomain?>
    
    /**
     * 도메인이 차단 목록에 있는지 확인합니다 (빠른 체크용).
     * @param domain 확인할 도메인
     * @return 차단되어 있으면 true
     */
    @Query("SELECT EXISTS(SELECT 1 FROM blocked_domains WHERE domain = :domain)")
    suspend fun isBlocked(domain: String): Boolean
    
    /**
     * 차단된 도메인을 추가합니다.
     * 이미 존재하면 무시합니다 (IGNORE).
     * @param blockedDomain 추가할 도메인
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlockedDomain(blockedDomain: BlockedDomain)
    
    /**
     * 차단된 도메인을 추가하거나 업데이트합니다.
     * 이미 존재하면 덮어씁니다 (REPLACE).
     * @param blockedDomain 추가/업데이트할 도메인
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateBlockedDomain(blockedDomain: BlockedDomain)
    
    /**
     * 차단된 도메인 정보를 업데이트합니다.
     * @param blockedDomain 업데이트할 도메인
     */
    @Update
    suspend fun updateBlockedDomain(blockedDomain: BlockedDomain)
    
    /**
     * 차단된 도메인을 삭제합니다.
     * @param blockedDomain 삭제할 도메인
     */
    @Delete
    suspend fun deleteBlockedDomain(blockedDomain: BlockedDomain)
    
    /**
     * 도메인 문자열로 차단을 해제합니다.
     * @param domain 해제할 도메인
     */
    @Query("DELETE FROM blocked_domains WHERE domain = :domain")
    suspend fun deleteBlockedDomainByDomain(domain: String)
    
    /**
     * 모든 차단 도메인을 삭제합니다.
     */
    @Query("DELETE FROM blocked_domains")
    suspend fun deleteAllBlockedDomains()
    
    /**
     * 차단된 도메인 개수를 반환합니다.
     * @return 차단된 도메인 수
     */
    @Query("SELECT COUNT(*) FROM blocked_domains")
    suspend fun getBlockedDomainCount(): Int
    
    /**
     * 도메인 패턴으로 검색합니다 (부분 일치).
     * @param pattern 검색 패턴 (LIKE 쿼리용, 예: "%youtube%")
     * @return 일치하는 차단 도메인 목록
     */
    @Query("SELECT * FROM blocked_domains WHERE domain LIKE :pattern ORDER BY blockedAt DESC")
    suspend fun searchBlockedDomains(pattern: String): List<BlockedDomain>
}
