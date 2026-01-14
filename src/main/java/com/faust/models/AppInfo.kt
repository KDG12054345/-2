package com.faust.models

import android.content.pm.ApplicationInfo

/**
 * 설치된 앱 정보를 담는 데이터 클래스입니다.
 * 앱 선택 다이얼로그에서 사용됩니다.
 * 
 * @param packageName 앱 패키지명
 * @param appName 앱 표시 이름
 * @param category 앱 카테고리 (ApplicationInfo.CATEGORY_*)
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED
) {
    /**
     * BlockedApp으로 변환합니다.
     */
    fun toBlockedApp(): BlockedApp {
        return BlockedApp(
            packageName = packageName,
            appName = appName
        )
    }
}
