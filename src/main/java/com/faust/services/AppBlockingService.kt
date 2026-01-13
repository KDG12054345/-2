package com.faust.services

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.presentation.view.GuiltyNegotiationOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * [시스템 진입점: 시스템 이벤트 진입점]
 * 
 * 역할: 안드로이드 시스템으로부터 앱 실행 상태 변화 신호를 받는 지점입니다. AccessibilityService를 상속받아 onAccessibilityEvent를 통해 시스템 이벤트를 직접 수신합니다.
 * 트리거: 접근성 서비스 활성화 시 onServiceConnected() 호출, 앱 실행 시 TYPE_WINDOW_STATE_CHANGED 이벤트 발생
 * 처리: 차단된 앱 목록 캐싱, 앱 실행 이벤트 실시간 감지, 차단된 앱 감지 시 오버레이 트리거
 * 
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class AppBlockingService : AccessibilityService(), LifecycleOwner {
    private val database: FaustDatabase by lazy {
        (application as FaustApplication).database
    }
    private var blockedAppsFlowJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentOverlay: GuiltyNegotiationOverlay? = null
    private var overlayDelayJob: Job? = null
    
    // 차단된 앱 목록을 메모리에 캐싱 (스레드 안전)
    private val blockedAppsCache = ConcurrentHashMap.newKeySet<String>()
    
    // LifecycleOwner 구현
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private val DELAY_BEFORE_OVERLAY_MS = 4000L..6000L // 4-6초 지연

        /**
         * 접근성 서비스가 활성화되어 있는지 확인
         */
        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            val serviceName = ComponentName(
                context.packageName,
                AppBlockingService::class.java.name
            ).flattenToString()
            
            return enabledServices.contains(serviceName)
        }

        /**
         * 접근성 서비스 설정 화면으로 이동
         */
        fun requestAccessibilityPermission(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }

    /**
     * [시스템 진입점: 시스템 이벤트 진입점]
     * 
     * 역할: 접근성 서비스가 시스템에 연결될 때 호출되는 진입점입니다.
     * 트리거: 사용자가 접근성 서비스 설정에서 Faust 서비스 활성화
     * 처리: Lifecycle 초기화, 차단 앱 목록 초기 로드 및 캐싱
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        initializeBlockedAppsCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        blockedAppsFlowJob?.cancel()
        overlayDelayJob?.cancel()
        serviceScope.cancel()
        hideOverlay()
        blockedAppsCache.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /**
     * [핵심 이벤트: 데이터 동기화 이벤트 - initializeBlockedAppsCache]
     * 
     * 역할: 차단 목록 데이터베이스에 변경이 생기면 AppBlockingService 내부의 HashSet 캐시를 즉시 업데이트하여 다음 앱 실행 감지에 반영하는 이벤트입니다.
     * 트리거: 서비스 시작 시 초기 로드, 차단 목록 데이터베이스 변경 시 Flow를 통해 자동 발생
     * 처리: 초기 로드 후 Flow 구독하여 변경사항 실시간 감지, 메모리 캐시(blockedAppsCache) 즉시 업데이트
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun initializeBlockedAppsCache() {
        blockedAppsFlowJob?.cancel()
        blockedAppsFlowJob = serviceScope.launch {
            try {
                // 초기 로드
                val initialApps = database.appBlockDao().getAllBlockedApps().first()
                blockedAppsCache.clear()
                blockedAppsCache.addAll(initialApps.map { it.packageName })
                
                // Flow를 구독하여 변경사항 실시간 감지
                database.appBlockDao().getAllBlockedApps().collect { apps ->
                    blockedAppsCache.clear()
                    blockedAppsCache.addAll(apps.map { it.packageName })
                }
            } catch (e: Exception) {
                // 에러 발생 시 빈 캐시로 시작
                blockedAppsCache.clear()
            }
        }
    }

    /**
     * [핵심 이벤트: 차단 관련 이벤트 - TYPE_WINDOW_STATE_CHANGED]
     * 
     * 역할: 사용자가 특정 앱(예: 유튜브)을 터치하여 화면 전환이 일어날 때 발생하는 접근성 이벤트를 처리합니다.
     * 트리거: 앱 실행 시 시스템이 TYPE_WINDOW_STATE_CHANGED 이벤트 발생
     * 처리: 패키지명 추출 후 handleAppLaunch() 호출
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            
            if (packageName != null) {
                handleAppLaunch(packageName)
            }
        }
    }

    /**
     * [핵심 이벤트: 차단 관련 이벤트 - handleAppLaunch]
     * 
     * 역할: 감지된 패키지 이름이 데이터베이스의 blocked_apps 테이블(메모리 캐시)에 존재하는지 대조하는 이벤트입니다.
     * 트리거: TYPE_WINDOW_STATE_CHANGED 이벤트에서 패키지명이 추출된 후 발생
     * 처리: 메모리 캐시에서 차단 여부 확인, 차단된 앱이면 4-6초 지연 후 오버레이 표시, 차단되지 않은 앱이면 오버레이 숨김
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun handleAppLaunch(packageName: String) {
        // 메모리 캐시에서 차단 여부 확인
        val isBlocked = blockedAppsCache.contains(packageName)
        
        if (isBlocked) {
            // 이전 지연 작업 취소
            overlayDelayJob?.cancel()
            
            // 차단된 앱 감지 - 4-6초 지연 후 오버레이 표시
            overlayDelayJob = serviceScope.launch {
                val delay = DELAY_BEFORE_OVERLAY_MS.random()
                delay(delay)
                
                if (isActive) {
                    val appName = getAppName(packageName)
                    showOverlay(packageName, appName)
                }
            }
        } else {
            // 차단되지 않은 앱이면 오버레이 숨김
            hideOverlay()
        }
    }

    /**
     * 접근성 서비스 인터럽트 처리
     */
    override fun onInterrupt() {
        // 접근성 서비스가 중단될 때 호출
        hideOverlay()
    }

    /**
     * [핵심 이벤트: 차단 관련 이벤트 - showOverlay]
     * 
     * 역할: 차단 대상 앱임이 확인되면 4~6초의 지연 후 GuiltyNegotiationOverlay를 화면 최상단에 띄우는 이벤트입니다.
     * 트리거: 차단된 앱 감지 후 4-6초 지연 시간 경과
     * 처리: GuiltyNegotiationOverlay 인스턴스 생성 및 WindowManager를 통해 시스템 레벨 오버레이 표시
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun showOverlay(packageName: String, appName: String) {
        serviceScope.launch(Dispatchers.Main) {
            if (currentOverlay == null) {
                currentOverlay = GuiltyNegotiationOverlay(this@AppBlockingService).apply {
                    show(packageName, appName)
                }
            }
        }
    }

    private fun hideOverlay() {
        serviceScope.launch(Dispatchers.Main) {
            currentOverlay?.dismiss()
            currentOverlay = null
        }
    }

    private suspend fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
