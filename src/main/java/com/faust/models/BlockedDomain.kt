package com.faust.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 차단된 도메인을 나타내는 Room 엔티티입니다.
 * 
 * 사용자가 URL 차단 목록에 추가한 도메인을 저장합니다.
 * 브라우저에서 해당 도메인 접속 시 차단됩니다.
 * 
 * @property domain 정규화된 도메인 (Primary Key, 예: "youtube.com")
 * @property displayName 사용자에게 표시할 이름 (선택, 예: "YouTube")
 * @property blockedAt 차단 추가 시각 (Unix timestamp, milliseconds)
 */
@Entity(tableName = "blocked_domains")
data class BlockedDomain(
    @PrimaryKey
    val domain: String,
    
    val displayName: String? = null,
    
    val blockedAt: Long = System.currentTimeMillis()
)
