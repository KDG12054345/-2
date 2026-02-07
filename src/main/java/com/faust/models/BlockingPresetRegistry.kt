package com.faust.models

/**
 * 원탭 차단 프리셋 목록을 제공하는 레지스트리입니다.
 * 
 * 인기 웹사이트를 한 번의 탭으로 차단할 수 있도록 미리 정의된 프리셋을 제공합니다.
 * 프리셋은 정적으로 정의되어 있으며, 필요 시 확장할 수 있습니다.
 * 
 * 참고: 아이콘 리소스(iconResId)는 res/drawable/에 추가해야 합니다.
 * 현재는 0으로 설정되어 있으며, 아이콘 표시 전에 유효성을 확인해야 합니다.
 */
object BlockingPresetRegistry {
    
    /**
     * 카테고리별 프리셋 그룹
     */
    enum class PresetCategory {
        SOCIAL,      // 소셜 미디어
        VIDEO,       // 동영상 플랫폼
        SHOPPING,    // 쇼핑
        NEWS,        // 뉴스
        GAMING,      // 게임
        OTHER        // 기타
    }
    
    /**
     * 모든 프리셋 목록을 반환합니다.
     * 
     * @return 모든 차단 프리셋 목록
     */
    fun getPresets(): List<BlockingPreset> = listOf(
        // === 소셜 미디어 ===
        BlockingPreset(
            id = "youtube",
            name = "YouTube",
            domain = "youtube.com",
            iconResId = 0 // TODO: R.drawable.ic_preset_youtube
        ),
        BlockingPreset(
            id = "facebook",
            name = "Facebook",
            domain = "facebook.com",
            iconResId = 0 // TODO: R.drawable.ic_preset_facebook
        ),
        BlockingPreset(
            id = "instagram",
            name = "Instagram",
            domain = "instagram.com",
            iconResId = 0 // TODO: R.drawable.ic_preset_instagram
        ),
        BlockingPreset(
            id = "twitter",
            name = "X (Twitter)",
            domain = "x.com",
            iconResId = 0 // TODO: R.drawable.ic_preset_twitter
        ),
        BlockingPreset(
            id = "tiktok",
            name = "TikTok",
            domain = "tiktok.com",
            iconResId = 0 // TODO: R.drawable.ic_preset_tiktok
        ),
        BlockingPreset(
            id = "reddit",
            name = "Reddit",
            domain = "reddit.com",
            iconResId = 0 // TODO: R.drawable.ic_preset_reddit
        ),
        
        // === 동영상 ===
        BlockingPreset(
            id = "netflix",
            name = "Netflix",
            domain = "netflix.com",
            iconResId = 0 // TODO: R.drawable.ic_preset_netflix
        ),
        BlockingPreset(
            id = "twitch",
            name = "Twitch",
            domain = "twitch.tv",
            iconResId = 0 // TODO: R.drawable.ic_preset_twitch
        ),
        
        // === 한국 서비스 ===
        BlockingPreset(
            id = "naver",
            name = "네이버",
            domain = "naver.com",
            iconResId = 0 // TODO: R.drawable.ic_preset_naver
        ),
        BlockingPreset(
            id = "daum",
            name = "다음",
            domain = "daum.net",
            iconResId = 0 // TODO: R.drawable.ic_preset_daum
        )
    )
    
    /**
     * 카테고리별 프리셋을 반환합니다.
     * 
     * @param category 카테고리
     * @return 해당 카테고리의 프리셋 목록
     */
    fun getPresetsByCategory(category: PresetCategory): List<BlockingPreset> {
        return when (category) {
            PresetCategory.SOCIAL -> getPresets().filter { 
                it.id in listOf("facebook", "instagram", "twitter", "tiktok", "reddit")
            }
            PresetCategory.VIDEO -> getPresets().filter { 
                it.id in listOf("youtube", "netflix", "twitch")
            }
            PresetCategory.SHOPPING -> emptyList() // 확장 가능
            PresetCategory.NEWS -> emptyList() // 확장 가능
            PresetCategory.GAMING -> emptyList() // 확장 가능
            PresetCategory.OTHER -> getPresets().filter { 
                it.id in listOf("naver", "daum")
            }
        }
    }
    
    /**
     * ID로 프리셋을 찾습니다.
     * 
     * @param id 프리셋 ID
     * @return 프리셋, 없으면 null
     */
    fun getPresetById(id: String): BlockingPreset? {
        return getPresets().find { it.id == id }
    }
    
    /**
     * 도메인으로 프리셋을 찾습니다.
     * 
     * @param domain 도메인
     * @return 프리셋, 없으면 null
     */
    fun getPresetByDomain(domain: String): BlockingPreset? {
        return getPresets().find { it.domain.equals(domain, ignoreCase = true) }
    }
}
