package com.faust.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.domain.PenaltyService
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
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(this)
    }
    private var blockedAppsFlowJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentOverlay: GuiltyNegotiationOverlay? = null
    private var overlayDelayJob: Job? = null
    
    // 차단된 앱 목록을 메모리에 캐싱 (스레드 안전)
    private val blockedAppsCache = ConcurrentHashMap.newKeySet<String>()
    
    // 페널티를 지불한 앱을 기억하는 변수 (Grace Period)
    private var lastAllowedPackage: String? = null
    
    // [추가] PenaltyService 인스턴스 (lazy 초기화)
    private val penaltyService: PenaltyService by lazy {
        PenaltyService(this)
    }
    
    // [추가] 현재 협상 중인 앱 정보 저장용
    private var currentBlockedPackage: String? = null
    private var currentBlockedAppName: String? = null
    
    // 화면 OFF 감지용 BroadcastReceiver
    private var screenOffReceiver: BroadcastReceiver? = null
    
    // LifecycleOwner 구현
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val TAG = "AppBlockingService" // [추가] 로그 태그 정의
        private val DELAY_BEFORE_OVERLAY_MS = 4000L..6000L // 4-6초 지연
        
        /**
         * 시스템 UI 패키지 목록 - 화면 꺼짐/잠금 시 실행되는 시스템 패키지를 무시하기 위함
         */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.keyguard"
        )

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
        registerScreenOffReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        blockedAppsFlowJob?.cancel()
        overlayDelayJob?.cancel()
        serviceScope.cancel()
        hideOverlay()
        blockedAppsCache.clear()
        unregisterScreenOffReceiver()
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
     * 허용된 패키지를 설정합니다.
     * 페널티를 지불한 앱은 오버레이가 다시 뜨지 않도록 합니다.
     */
    fun setAllowedPackage(packageName: String?) {
        lastAllowedPackage = packageName
    }

    /**
     * [핵심 이벤트: 차단 관련 이벤트 - handleAppLaunch]
     * 
     * 역할: 감지된 패키지 이름이 데이터베이스의 blocked_apps 테이블(메모리 캐시)에 존재하는지 대조하는 이벤트입니다.
     * 트리거: TYPE_WINDOW_STATE_CHANGED 이벤트에서 패키지명이 추출된 후 발생
     * 처리: 메모리 캐시에서 차단 여부 확인, 차단된 앱이면 즉시 포인트 적립 중단 및 4-6초 지연 후 오버레이 표시, 차단되지 않은 앱이면 포인트 적립 재개
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun handleAppLaunch(packageName: String) {
        // 1. 오버레이가 활성화 상태라면, 키보드나 다른 앱이 감지되어도 차단을 풀지 않고 무시함
        if (currentOverlay != null) {
            Log.d(TAG, "오버레이 활성 상태: 패키지 변경 무시 ($packageName)")
            return
        }
        
        // 2. 시스템 UI(상단바, 잠금화면)는 무시
        if (packageName in IGNORED_PACKAGES) {
            Log.d(TAG, "시스템 UI 패키지 무시: $packageName")
            return
        }
        
        // 메모리 캐시에서 차단 여부 확인
        val isBlocked = blockedAppsCache.contains(packageName)
        
        if (isBlocked) {
            // Step 1: 즉시 포인트 적립 중단
            PointMiningService.pauseMining()
            Log.d(TAG, "Mining Paused: 차단 앱 감지 ($packageName)")
            
            // 차단 앱 감지 시 PreferenceManager에 저장 (오디오 감지 정확도 향상)
            preferenceManager.setLastMiningApp(packageName)
            Log.d(TAG, "Last mining app updated: $packageName")
            
            // Step 2: Grace Period 체크 (벌금 지불 완료)
            if (packageName == lastAllowedPackage) {
                Log.d(TAG, "Grace Period: 오버레이 표시 안 함")
                return
            }
            
            // Step 3: 오버레이 예약 (4-6초 지연)
            overlayDelayJob?.cancel()
            overlayDelayJob = serviceScope.launch {
                val delay = DELAY_BEFORE_OVERLAY_MS.random()
                delay(delay)
                
                if (isActive) {
                    val appName = getAppName(packageName)
                    showOverlay(packageName, appName)
                }
            }
        } else {
            // 허용 앱: 포인트 적립 재개
            PointMiningService.resumeMining()
            Log.d(TAG, "Mining Resumed: 허용 앱으로 전환")
            
            // 허용 앱으로 전환 시 PreferenceManager에 저장
            preferenceManager.setLastMiningApp(packageName)
            Log.d(TAG, "Last mining app updated: $packageName")
            
            // Grace Period 초기화 및 오버레이 숨김
            lastAllowedPackage = null
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
        // [추가] 현재 차단된 앱 정보 저장 (화면 OFF 시 철회 처리를 위해)
        // 코루틴 밖에서 실행하여 즉시 저장
        this.currentBlockedPackage = packageName
        this.currentBlockedAppName = appName

        serviceScope.launch(Dispatchers.Main) {
            if (currentOverlay == null) {
                currentOverlay = GuiltyNegotiationOverlay(this@AppBlockingService).apply {
                    show(packageName, appName)
                }
            }
        }
    }

    fun hideOverlay() { // private 제거
        serviceScope.launch(Dispatchers.Main) {
            currentOverlay?.dismiss(force = true) // 강제 종료 플래그 전달
            currentOverlay = null // 핵심: 참조를 null로 만들어 중복 감지 방지
            
            // [추가] 앱 정보 초기화
            currentBlockedPackage = null
            currentBlockedAppName = null
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
    
    /**
     * 화면 OFF 감지용 BroadcastReceiver를 등록합니다.
     */
    private fun registerScreenOffReceiver() {
        screenOffReceiver = object : BroadcastReceiver() {
            /**
             * 강화된 홈 화면 이동 로직
             * 화면 OFF 상태에서는 Intent 방식을 우선 시도하고, 동시에 performGlobalAction도 호출합니다.
             * @param contextLabel 로그에 사용할 컨텍스트 레이블 (예: "협상 중", "차단 상태")
             */
            private fun navigateToHome(contextLabel: String) {
                Log.d(TAG, "화면 OFF 시 홈 이동 시작 ($contextLabel)")
                
                // 화면이 꺼진 상태에서는 Intent 방식이 더 확실하므로 우선 시도
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                
                try {
                    this@AppBlockingService.startActivity(homeIntent)
                    Log.d(TAG, "화면 OFF 시 Intent 방식으로 홈 이동 시도 ($contextLabel)")
                } catch (e: Exception) {
                    Log.e(TAG, "화면 OFF 시 Intent 방식 홈 이동 실패 ($contextLabel)", e)
                }
                
                // 동시에 performGlobalAction도 호출 (이중 보장)
                val homeResult = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                if (homeResult) {
                    Log.d(TAG, "화면 OFF 시 performGlobalAction 성공 ($contextLabel)")
                } else {
                    Log.e(TAG, "화면 OFF 시 performGlobalAction 실패 ($contextLabel)")
                    
                    // 실패 시 100ms 지연 후 재시도
                    serviceScope.launch {
                        delay(100)
                        
                        // Intent 재시도
                        try {
                            this@AppBlockingService.startActivity(homeIntent)
                            Log.d(TAG, "화면 OFF 시 Intent 방식 재시도 ($contextLabel)")
                        } catch (e: Exception) {
                            Log.e(TAG, "화면 OFF 시 Intent 방식 재시도 실패 ($contextLabel)", e)
                        }
                        
                        // performGlobalAction 재시도
                        val retryResult = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        if (retryResult) {
                            Log.d(TAG, "화면 OFF 시 performGlobalAction 재시도 성공 ($contextLabel)")
                        } else {
                            Log.e(TAG, "화면 OFF 시 performGlobalAction 재시도 실패 ($contextLabel)")
                        }
                    }
                }
            }
            
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    
                    // Case 1: 협상 중(오버레이 뜸)에 화면 끔 -> '철회'로 간주
                    if (currentOverlay != null) {
                        Log.d(TAG, "협상 중 도주 감지: 철회 패널티 부과")

                        // 필요한 데이터 로컬 변수에 캡처 (비동기 처리를 위해)
                        val targetPackage = currentBlockedPackage
                        val targetAppName = currentBlockedAppName ?: targetPackage ?: "Unknown App"

                        // 1. 비동기: 철회 패널티 적용 (DB 작업)
                        serviceScope.launch {
                            if (targetPackage != null) {
                                try {
                                    penaltyService.applyQuitPenalty(targetPackage, targetAppName)
                                    Log.d(TAG, "화면 OFF 철회 패널티 적용 완료: $targetAppName")
                                } catch (e: Exception) {
                                    Log.e(TAG, "철회 패널티 적용 실패", e)
                                }
                            }
                        }

                        // 2. 동기: UI 및 시스템 상태 즉시 정리 (반응성 확보)
                        // 오버레이 강제 종료 (force = true)
                        currentOverlay?.dismiss(force = true)
                        currentOverlay = null
                        
                        // 변수 초기화
                        currentBlockedPackage = null
                        currentBlockedAppName = null

                        // 홈 화면 이동 및 적립 재개
                        navigateToHome("협상 중")
                        PointMiningService.resumeMining()
                    }
                    // Case 2: 오버레이 없이 차단 상태 (Grace Period 또는 로딩 중) -> 그냥 홈 이동
                    else if (PointMiningService.isMiningPaused()) {
                        Log.d(TAG, "차단 상태(오버레이 없음)에서 화면 OFF -> 홈 이동")
                        navigateToHome("차단 상태")
                        PointMiningService.resumeMining()
                    }
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
        Log.d(TAG, "Screen OFF Receiver Registered")
    }
    
    /**
     * 화면 OFF 감지용 BroadcastReceiver를 해제합니다.
     */
    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            try {
                unregisterReceiver(it)
                screenOffReceiver = null
                Log.d(TAG, "Screen OFF Receiver Unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen off receiver", e)
            }
        }
    }
}
