package com.faust.models

import androidx.room.Entity

/**
 * 앱 그룹 정의 엔티티
 * 카테고리 기반 + 특정 패키지명 추가/제외를 지원합니다.
 */
@Entity(
    tableName = "app_groups",
    primaryKeys = ["packageName", "groupType"]
)
data class AppGroup(
    val packageName: String,                  // 앱 패키지명
    val groupType: AppGroupType,              // 그룹 타입 (SNS, OTT)
    val isIncluded: Boolean = true            // 포함 여부 (true: 포함, false: 제외)
)
