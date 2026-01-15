package com.faust.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * 역할: 안드로이드 시스템으로부터 앱 실행 상태 변화 신호를 받는 지점입니다.
 * 처리: 차단된 앱 목록 캐싱, 앱 실행 이벤트 실시간 감지, 차단된 앱 감지 시 오버레이 트리거
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

    // 차단된 앱 목록을 메모리에 캐싱
    private val blockedAppsCache = ConcurrentHashMap.newKeySet<String>()

    // 페널티를 지불한 앱을 기억하는 변수 (Grace Period)
    private var lastAllowedPackage: String? = null

    // PenaltyService 인스턴스
    private val penaltyService: PenaltyService by lazy {
        PenaltyService(this)
    }

    // 현재 협상 중인 앱 정보 저장용
    private var currentBlockedPackage: String? = null
    private var currentBlockedAppName: String? = null

    // 화면 OFF 감지용 BroadcastReceiver
    private var screenOffReceiver: BroadcastReceiver? = null

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val TAG = "AppBlockingService"
        private val DELAY_BEFORE_OVERLAY_MS = 4000L..6000L // 4-6초 지연

        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.keyguard"
        )

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

        fun requestAccessibilityPermission(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }

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
        hideOverlay(shouldGoHome = false)
        blockedAppsCache.clear()
        unregisterScreenOffReceiver()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun initializeBlockedAppsCache() {
        blockedAppsFlowJob?.cancel()
        blockedAppsFlowJob = serviceScope.launch {
            try {
                val initialApps = database.appBlockDao().getAllBlockedApps().first()
                blockedAppsCache.clear()
                blockedAppsCache.addAll(initialApps.map { it.packageName })

                database.appBlockDao().getAllBlockedApps().collect { apps ->
                    blockedAppsCache.clear()
                    blockedAppsCache.addAll(apps.map { it.packageName })
                }
            } catch (e: Exception) {
                blockedAppsCache.clear()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null) {
                handleAppLaunch(packageName)
            }
        }
    }

    fun setAllowedPackage(packageName: String?) {
        lastAllowedPackage = packageName
    }

    private fun handleAppLaunch(packageName: String) {
        if (currentOverlay != null) {
            Log.d(TAG, "오버레이 활성 상태: 패키지 변경 무시 ($packageName)")
            return
        }

        if (packageName in IGNORED_PACKAGES) return

        val isBlocked = blockedAppsCache.contains(packageName)

        if (isBlocked) {
            PointMiningService.pauseMining()
            Log.d(TAG, "Mining Paused: 차단 앱 감지 ($packageName)")

            preferenceManager.setLastMiningApp(packageName)

            if (packageName == lastAllowedPackage) {
                Log.d(TAG, "Grace Period: 오버레이 표시 안 함")
                return
            }

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
            PointMiningService.resumeMining()
            Log.d(TAG, "Mining Resumed: 허용 앱으로 전환")
            preferenceManager.setLastMiningApp(packageName)
            lastAllowedPackage = null
            hideOverlay(shouldGoHome = false)
        }
    }

    override fun onInterrupt() {
        hideOverlay(shouldGoHome = false)
    }

    private fun showOverlay(packageName: String, appName: String) {
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

    /**
     * [핵심 수정] 오버레이를 닫고, 필요 시 홈으로 이동시킵니다.
     * 외부(Overlay)에서 호출 가능하도록 public으로 변경되었습니다.
     */
    fun hideOverlay(shouldGoHome: Boolean = false) {
        serviceScope.launch(Dispatchers.Main) {
            // 1. 홈 이동 요청이 있으면 실행
            if (shouldGoHome) {
                navigateToHome("오버레이 종료 요청")
            }

            // 2. 오버레이 닫기 및 참조 제거 (중복 차감 방지 핵심)
            currentOverlay?.dismiss(force = true)
            currentOverlay = null

            // 3. 앱 정보 초기화
            currentBlockedPackage = null
            currentBlockedAppName = null
        }
    }

    /**
     * [핵심 수정] 홈 화면 이동 로직을 클래스 멤버 함수로 분리 (공용 사용)
     */
    fun navigateToHome(contextLabel: String) {
        Log.d(TAG, "홈 이동 실행 ($contextLabel)")

        // 1. Intent 방식 시도
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        try {
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Intent 홈 이동 실패", e)
        }

        // 2. Global Action 방식 시도 (이중 보장)
        performGlobalAction(GLOBAL_ACTION_HOME)
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

    private fun registerScreenOffReceiver() {
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {

                    // Case 1: 협상 중(오버레이 뜸)에 화면 끔 -> 도주 감지
                    if (currentOverlay != null) {
                        Log.d(TAG, "협상 중 도주 감지: 철회 패널티 부과")

                        val targetPackage = currentBlockedPackage
                        val targetAppName = currentBlockedAppName ?: "Unknown App"

                        // 비동기: 철회 패널티 적용
                        serviceScope.launch {
                            if (targetPackage != null) {
                                try {
                                    penaltyService.applyQuitPenalty(targetPackage, targetAppName)
                                } catch (e: Exception) {
                                    Log.e(TAG, "철회 패널티 적용 실패", e)
                                }
                            }
                        }

                        // [핵심] 서비스를 통해 홈으로 보내고 오버레이 정리 (shouldGoHome = true)
                        hideOverlay(shouldGoHome = true)
                        PointMiningService.resumeMining()
                    }
                    // Case 2: 오버레이 없이 차단 상태 -> 그냥 홈 이동
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