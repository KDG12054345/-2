package com.faust.models

import androidx.annotation.DrawableRes

/**
 * 원탭 차단 프리셋을 나타내는 데이터 클래스입니다.
 * 
 * 인기 사이트(YouTube, Facebook 등)를 한 번의 탭으로 차단 목록에 추가할 수 있도록
 * 미리 정의된 데이터입니다. Room 엔티티가 아닌 정적/구성 데이터입니다.
 * 
 * @property id 고유 식별자 (예: "youtube", "facebook")
 * @property name 표시 이름 (예: "YouTube", "Facebook")
 * @property domain 정규화된 도메인 (예: "youtube.com")
 * @property iconResId Android drawable 리소스 ID (아이콘)
 */
data class BlockingPreset(
    val id: String,
    val name: String,
    val domain: String,
    @DrawableRes val iconResId: Int
)
