package com.faust.utils

import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * 앱 카테고리 관련 유틸리티 함수들입니다.
 */
object AppCategoryUtils {
    /**
     * 크롬 브라우저 패키지명
     */
    private const val CHROME_PACKAGE_NAME = "com.android.chrome"

    /**
     * ApplicationInfo의 category를 가져옵니다.
     * API 26 미만에서는 CATEGORY_UNDEFINED를 반환합니다.
     * 크롬은 소셜 카테고리에서 제외하고 브라우저로 분류합니다.
     */
    fun getAppCategory(applicationInfo: ApplicationInfo): Int {
        // 크롬을 소셜 카테고리에서 제외하고 브라우저로 분류
        if (applicationInfo.packageName == CHROME_PACKAGE_NAME) {
            return CATEGORY_BROWSER
        }
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationInfo.category
        } else {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
    }

    /**
     * 카테고리 상수 정의
     */
    const val CATEGORY_ALL = -1
    const val CATEGORY_SOCIAL = ApplicationInfo.CATEGORY_SOCIAL
    const val CATEGORY_GAME = ApplicationInfo.CATEGORY_GAME
    const val CATEGORY_VIDEO = ApplicationInfo.CATEGORY_VIDEO
    const val CATEGORY_BROWSER = -2  // 브라우저 카테고리 (고유한 값)
    const val CATEGORY_OTHER = ApplicationInfo.CATEGORY_UNDEFINED

    /**
     * 카테고리 이름을 반환합니다.
     */
    fun getCategoryName(category: Int): String {
        return when (category) {
            CATEGORY_ALL -> "전체"
            CATEGORY_SOCIAL -> "소셜"
            CATEGORY_GAME -> "게임"
            CATEGORY_VIDEO -> "비디오"
            CATEGORY_BROWSER -> "브라우저"
            CATEGORY_OTHER -> "기타"
            else -> "기타"
        }
    }

    /**
     * 카테고리 이모지를 반환합니다.
     */
    fun getCategoryEmoji(category: Int): String {
        return when (category) {
            CATEGORY_ALL -> "📱"
            CATEGORY_SOCIAL -> "💬"
            CATEGORY_GAME -> "🎮"
            CATEGORY_VIDEO -> "🎬"
            CATEGORY_BROWSER -> "🌐"
            CATEGORY_OTHER -> "📂"
            else -> "📂"
        }
    }

    /**
     * 앱이 특정 카테고리에 속하는지 확인합니다.
     */
    fun matchesCategory(appCategory: Int, filterCategory: Int): Boolean {
        return when (filterCategory) {
            CATEGORY_ALL -> true
            CATEGORY_OTHER -> appCategory == ApplicationInfo.CATEGORY_UNDEFINED || 
                             (appCategory != CATEGORY_SOCIAL && 
                              appCategory != CATEGORY_GAME && 
                              appCategory != CATEGORY_VIDEO &&
                              appCategory != CATEGORY_BROWSER)
            else -> appCategory == filterCategory
        }
    }
}
