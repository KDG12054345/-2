package com.faust.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
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

    // 화면이 꺼진 동안 홈 이동이 예약되었는지를 추적하는 플래그
    private var isPendingHomeNavigation: Boolean = false

    // 화면 OFF 감지용 BroadcastReceiver
    private var screenOffReceiver: BroadcastReceiver? = null

    // Cool-down 관련 변수 (이중 협상 방지)
    private var lastHomeNavigationTime: Long = 0
    private var lastHomeNavigationPackage: String? = null

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val TAG = "AppBlockingService"
        private val DELAY_BEFORE_OVERLAY_MS = 4000L..6000L // 4-6초 지연
        private const val COOLDOWN_DURATION_MS = 1000L // 1초 Cool-down (이중 협상 방지)
        private const val DELAY_AFTER_OVERLAY_DISMISS_MS = 150L // 오버레이 닫은 후 홈 이동 지연 (영상 재생 중 화면 축소 방지)

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

        // Cool-down 체크: 홈 이동 후 짧은 시간 동안 같은 앱에 대한 오버레이 차단
        val now = System.currentTimeMillis()
        if (lastHomeNavigationPackage == packageName && 
            (now - lastHomeNavigationTime) < COOLDOWN_DURATION_MS) {
            val remainingTime = COOLDOWN_DURATION_MS - (now - lastHomeNavigationTime)
            Log.d(TAG, "Cool-down 활성: $packageName 오버레이 차단 (남은 시간: ${remainingTime}ms)")
            return
        }

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
     * 
     * [수정] 영상 재생 중 철회 버튼 클릭 시 화면 축소 문제 해결:
     * - 오버레이를 먼저 닫고, 짧은 지연 후 홈으로 이동하여 전체화면 모드 해제를 방지합니다.
     * [수정] 유죄협상 중복 진행 방지:
     * - currentBlockedPackage를 null로 설정하기 전에 먼저 저장하여 Cool-down 설정 보장
     */
    fun hideOverlay(shouldGoHome: Boolean = false) {
        serviceScope.launch(Dispatchers.Main) {
            // 1. 홈 이동 전에 Cool-down 정보 저장 (currentBlockedPackage가 null이 되기 전)
            val blockedPackageForCoolDown = if (shouldGoHome) currentBlockedPackage else null

            // 2. 오버레이 닫기 및 참조 제거 (중복 차감 방지 핵심)
            currentOverlay?.dismiss(force = true)
            currentOverlay = null

            // 3. 앱 정보 초기화
            currentBlockedPackage = null
            currentBlockedAppName = null

            // 4. 홈 이동 요청이 있으면 오버레이 닫힌 후 지연하여 실행
            // 영상 재생 중 전체화면 모드에서 화면 축소 현상을 방지하기 위해 지연 추가
            if (shouldGoHome) {
                delay(DELAY_AFTER_OVERLAY_DISMISS_MS)
                navigateToHome("오버레이 종료 요청", blockedPackageForCoolDown)
            }
        }
    }

    /**
     * [핵심 수정] 홈 화면 이동 로직을 클래스 멤버 함수로 분리 (공용 사용)
     * 
     * @param contextLabel 홈 이동 컨텍스트 레이블 (로깅용)
     * @param blockedPackageForCoolDown Cool-down 설정을 위한 차단된 앱 패키지명 (null 가능)
     */
    fun navigateToHome(contextLabel: String, blockedPackageForCoolDown: String? = null) {
        Log.d(TAG, "홈 이동 실행 ($contextLabel)")

        // 화면 상태 확인 (PowerManager 사용)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            // 화면이 꺼진 상태: 지연 처리
            Log.d(TAG, "화면 OFF 상태: 홈 이동 예약")
            isPendingHomeNavigation = true
            return
        }

        // Cool-down 시작: 차단된 앱 정보 저장
        // 파라미터로 전달된 패키지명이 있으면 우선 사용, 없으면 currentBlockedPackage 사용 (폴백 로직)
        val blockedPackage = blockedPackageForCoolDown ?: currentBlockedPackage
        if (blockedPackage != null) {
            lastHomeNavigationTime = System.currentTimeMillis()
            lastHomeNavigationPackage = blockedPackage
            Log.d(TAG, "Cool-down 시작: $blockedPackage (${COOLDOWN_DURATION_MS}ms)")
        }

        // GlobalAction만 사용 (Intent 방식 제거)
        val success = performGlobalAction(GLOBAL_ACTION_HOME)
        if (!success) {
            Log.e(TAG, "GlobalAction 홈 이동 실패")
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

    private fun registerScreenOffReceiver() {
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
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

                            // 오버레이 닫기 (홈 이동은 즉시 하지 않음)
                            hideOverlay(shouldGoHome = false)
                            
                            // 홈 이동 예약 (화면이 켜질 때 실행)
                            isPendingHomeNavigation = true
                            
                            // 채굴은 이미 pause 상태이므로 유지
                            Log.d(TAG, "홈 이동 예약됨 (화면 ON 시 실행)")
                        }
                        // Case 2: 오버레이 없이 차단 상태 -> 홈 이동 예약
                        else if (PointMiningService.isMiningPaused()) {
                            Log.d(TAG, "차단 상태(오버레이 없음)에서 화면 OFF -> 홈 이동 예약")
                            
                            // 홈 이동 예약 (화면이 켜질 때 실행)
                            isPendingHomeNavigation = true
                            
                            // 채굴은 이미 pause 상태이므로 유지
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        // 화면이 켜질 때 예약된 홈 이동 실행
                        if (isPendingHomeNavigation) {
                            Log.d(TAG, "화면 ON: 예약된 홈 이동 실행")
                            navigateToHome("화면 ON 이벤트")
                            isPendingHomeNavigation = false
                            
                            // 채굴 재개
                            PointMiningService.resumeMining()
                            Log.d(TAG, "Mining Resumed: 화면 ON 후 홈 이동 완료")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenOffReceiver, filter)
        Log.d(TAG, "Screen ON/OFF Receiver Registered")
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