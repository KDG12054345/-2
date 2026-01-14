package com.faust.presentation.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.faust.models.AppInfo
import com.faust.utils.AppCategoryUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 앱 선택 다이얼로그의 데이터 관찰 및 필터링 로직을 담당하는 ViewModel입니다.
 * 
 * 역할:
 * - 설치된 앱 목록 로드
 * - 카테고리별 필터링
 * - 필터링된 앱 목록 StateFlow로 노출
 */
class AppSelectionViewModel(application: Application) : AndroidViewModel(application) {

    // 전체 앱 목록 (필터링 전)
    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    
    // 필터링된 앱 목록
    private val _filteredApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val filteredApps: StateFlow<List<AppInfo>> = _filteredApps.asStateFlow()

    // 현재 선택된 카테고리
    private val _selectedCategory = MutableStateFlow<Int>(AppCategoryUtils.CATEGORY_ALL)
    val selectedCategory: StateFlow<Int> = _selectedCategory.asStateFlow()

    init {
        loadInstalledApps()
    }

    /**
     * 설치된 모든 앱을 로드하고 AppInfo 리스트로 변환합니다.
     * 각 앱의 카테고리 정보를 포함합니다.
     */
    fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { 
                        // 런처 아이콘이 있는 앱만 표시 (실제 사용 가능한 앱)
                        try {
                            pm.getLaunchIntentForPackage(it.packageName) != null
                        } catch (e: Exception) {
                            false
                        }
                    }
                    .map { applicationInfo ->
                        val category = AppCategoryUtils.getAppCategory(applicationInfo)
                        AppInfo(
                            packageName = applicationInfo.packageName,
                            appName = pm.getApplicationLabel(applicationInfo).toString(),
                            category = category
                        )
                    }
                    .sortedBy { it.appName } // 앱 이름순 정렬
            }
            _allApps.value = apps
            // 초기 로드 시 전체 앱 표시
            filterAppsByCategory(AppCategoryUtils.CATEGORY_ALL)
        }
    }

    /**
     * 선택된 카테고리에 따라 앱 목록을 필터링합니다.
     * @param category 필터링할 카테고리 (AppCategoryUtils.CATEGORY_*)
     */
    fun filterAppsByCategory(category: Int) {
        _selectedCategory.value = category
        val filtered = _allApps.value.filter { app ->
            AppCategoryUtils.matchesCategory(app.category, category)
        }
        _filteredApps.value = filtered
    }
}
