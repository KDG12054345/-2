package com.faust.domain

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.models.AppGroup
import com.faust.models.AppGroupType
import com.faust.utils.AppCategoryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 앱 그룹 관리 서비스
 * SNS/OTT 앱 그룹을 관리하고, 앱이 특정 그룹에 속하는지 확인합니다.
 */
class AppGroupService(private val context: Context) {
    private val database: FaustDatabase by lazy {
        (context.applicationContext as FaustApplication).database
    }

    companion object {
        private const val TAG = "AppGroupService"

        /**
         * 주요 SNS 앱 패키지명 목록
         */
        private val DEFAULT_SNS_PACKAGES = listOf(
            "com.facebook.katana",           // Facebook
            "com.instagram.android",         // Instagram
            "com.twitter.android",           // Twitter
            "com.snapchat.android",          // Snapchat
            "com.kakao.talk",                // 카카오톡
            "com.nhn.android.naver",         // 네이버
            "com.naver.line",                // LINE
            "com.tencent.mm",                // WeChat
            "com.whatsapp",                  // WhatsApp
            "com.telegram.messenger"         // Telegram
        )

        /**
         * 주요 OTT 앱 패키지명 목록
         */
        private val DEFAULT_OTT_PACKAGES = listOf(
            "com.netflix.mediaclient",       // Netflix
            "com.disney.disneyplus",         // Disney+
            "com.amazon.avod.thirdpartyclient", // Amazon Prime Video
            "com.hulu.plus",                 // Hulu
            "com.hbogo.android",             // HBO GO
            "com.paramountplus.app",         // Paramount+
            "com.apple.atve.appletv",        // Apple TV
            "com.youtube",                   // YouTube
            "com.google.android.youtube",    // YouTube (alternative)
            "com.wavve.android",            // 웨이브
            "com.tving",                     // 티빙
            "com.cjenm.sonm",                // 쿠팡플레이
            "com.discovery.discoveryplus"    // Discovery+
        )
    }

    /**
     * 앱이 특정 그룹에 속하는지 확인합니다.
     * 카테고리 기반 + 특정 패키지명 추가/제외를 모두 고려합니다.
     * 
     * @param packageName 확인할 앱 패키지명
     * @param groupType 확인할 그룹 타입 (SNS 또는 OTT)
     * @return 앱이 그룹에 속하면 true, 아니면 false
     */
    suspend fun isAppInGroup(packageName: String, groupType: AppGroupType): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. DB에서 명시적으로 포함/제외된 앱 확인
                val explicitGroup = database.appGroupDao().isAppInGroup(packageName, groupType)
                if (explicitGroup != null) {
                    return@withContext explicitGroup.isIncluded
                }

                // 2. 카테고리 기반 확인
                val packageManager = context.packageManager
                val appInfo = try {
                    packageManager.getApplicationInfo(packageName, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Package not found: $packageName", e)
                    return@withContext false
                }

                val appCategory = AppCategoryUtils.getAppCategory(appInfo)
                val matchesCategory = when (groupType) {
                    AppGroupType.SNS -> appCategory == AppCategoryUtils.CATEGORY_SOCIAL
                    AppGroupType.OTT -> appCategory == AppCategoryUtils.CATEGORY_VIDEO
                }

                matchesCategory
            } catch (e: Exception) {
                Log.e(TAG, "Error checking if app is in group: $packageName, $groupType", e)
                false
            }
        }
    }

    /**
     * 기본 앱 그룹을 초기화합니다.
     * 데이터가 없을 때만 초기화합니다.
     */
    suspend fun initializeDefaultGroups() {
        withContext(Dispatchers.IO) {
            try {
                // 기존 데이터 확인
                val existingGroups = database.appGroupDao().getAllGroups().first()
                if (existingGroups.isNotEmpty()) {
                    Log.d(TAG, "App groups already initialized, skipping")
                    return@withContext
                }

                val groupsToInsert = mutableListOf<AppGroup>()

                // SNS 앱 그룹 초기화
                DEFAULT_SNS_PACKAGES.forEach { packageName ->
                    // 앱이 설치되어 있는지 확인
                    if (isPackageInstalled(packageName)) {
                        groupsToInsert.add(
                            AppGroup(
                                packageName = packageName,
                                groupType = AppGroupType.SNS,
                                isIncluded = true
                            )
                        )
                    }
                }

                // OTT 앱 그룹 초기화
                DEFAULT_OTT_PACKAGES.forEach { packageName ->
                    // 앱이 설치되어 있는지 확인
                    if (isPackageInstalled(packageName)) {
                        groupsToInsert.add(
                            AppGroup(
                                packageName = packageName,
                                groupType = AppGroupType.OTT,
                                isIncluded = true
                            )
                        )
                    }
                }

                if (groupsToInsert.isNotEmpty()) {
                    database.appGroupDao().insertOrUpdateGroups(groupsToInsert)
                    Log.d(TAG, "Initialized ${groupsToInsert.size} app groups")
                } else {
                    Log.d(TAG, "No apps found to initialize groups")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing default groups", e)
            }
        }
    }

    /**
     * 패키지가 설치되어 있는지 확인합니다.
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 앱 그룹에 앱을 추가합니다.
     */
    suspend fun addAppToGroup(packageName: String, groupType: AppGroupType, isIncluded: Boolean = true) {
        withContext(Dispatchers.IO) {
            try {
                val group = AppGroup(
                    packageName = packageName,
                    groupType = groupType,
                    isIncluded = isIncluded
                )
                database.appGroupDao().insertOrUpdateGroup(group)
                Log.d(TAG, "Added app to group: $packageName, $groupType, isIncluded=$isIncluded")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding app to group: $packageName, $groupType", e)
            }
        }
    }

    /**
     * 앱 그룹에서 앱을 제거합니다.
     */
    suspend fun removeAppFromGroup(packageName: String, groupType: AppGroupType) {
        withContext(Dispatchers.IO) {
            try {
                val group = AppGroup(
                    packageName = packageName,
                    groupType = groupType,
                    isIncluded = false
                )
                database.appGroupDao().insertOrUpdateGroup(group)
                Log.d(TAG, "Removed app from group: $packageName, $groupType")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing app from group: $packageName, $groupType", e)
            }
        }
    }
}
