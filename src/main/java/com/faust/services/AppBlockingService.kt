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

    // 오버레이 닫기 중 플래그 (중복 오버레이 생성 방지)
    @Volatile
    private var isOverlayDismissing: Boolean = false

    // 쿨다운 메커니즘 (중복 유죄협상 방지)
    private var lastHomeNavigationPackage: String? = null
    private var lastHomeNavigationTime: Long = 0L
    private val COOLDOWN_DURATION_MS = 1000L // 1초
    private val DELAY_AFTER_OVERLAY_DISMISS_MS = 150L // 오버레이 닫은 후 홈 이동 지연 시간

    // 상태전이 시스템 (State Transition System)
    enum class MiningState {
        ALLOWED,  // 포인트 채굴 활성화
        BLOCKED   // 포인트 채굴 중단
    }
    
    @Volatile
    private var currentMiningState: MiningState = MiningState.ALLOWED

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val TAG = "AppBlockingService"

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
        // 상태전이 시스템: PointMiningService에 콜백 등록
        PointMiningService.setBlockingServiceCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        blockedAppsFlowJob?.cancel()
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
            // 쿨다운 체크: 같은 앱이 최근에 홈으로 이동했고 쿨다운 시간 내면 오버레이 표시 차단
            val currentTime = System.currentTimeMillis()
            if (packageName == lastHomeNavigationPackage && 
                (currentTime - lastHomeNavigationTime) < COOLDOWN_DURATION_MS) {
                Log.d(TAG, "Cool-down 활성: 오버레이 표시 차단 ($packageName)")
                return
            }

            // 상태전이 시스템: ALLOWED → BLOCKED 전이
            transitionToState(MiningState.BLOCKED, packageName, triggerOverlay = true)
        } else {
            // 상태전이 시스템: BLOCKED → ALLOWED 전이
            transitionToState(MiningState.ALLOWED, packageName, triggerOverlay = false)
        }
    }

    override fun onInterrupt() {
        hideOverlay(shouldGoHome = false)
    }

    private fun showOverlay(packageName: String, appName: String) {
        // 동기 체크: 오버레이가 이미 있거나 닫는 중이면 즉시 반환
        if (currentOverlay != null || isOverlayDismissing) {
            Log.d(TAG, "오버레이 생성 차단: currentOverlay=${currentOverlay != null}, isOverlayDismissing=$isOverlayDismissing")
            return
        }

        this.currentBlockedPackage = packageName
        this.currentBlockedAppName = appName

        serviceScope.launch(Dispatchers.Main) {
            // 비동기 이중 체크: 경쟁 조건 방지
            if (currentOverlay == null && !isOverlayDismissing) {
                currentOverlay = GuiltyNegotiationOverlay(this@AppBlockingService).apply {
                    show(packageName, appName)
                }
            } else {
                Log.d(TAG, "오버레이 생성 차단 (비동기 체크): currentOverlay=${currentOverlay != null}, isOverlayDismissing=$isOverlayDismissing")
            }
        }
    }

    /**
     * [핵심 수정] 오버레이를 닫고, 필요 시 홈으로 이동시킵니다.
     * 외부(Overlay)에서 호출 가능하도록 public으로 변경되었습니다.
     */
    fun hideOverlay(shouldGoHome: Boolean = false) {
        serviceScope.launch(Dispatchers.Main) {
            // 1. 닫기 중 플래그 설정 (경쟁 조건 방지)
            isOverlayDismissing = true

            // 2. 패키지 정보 백업 (쿨다운 설정용)
            val blockedPackageForCoolDown = currentBlockedPackage

            // 3. 오버레이 닫기 및 참조 제거 (중복 차감 방지 핵심)
            currentOverlay?.dismiss(force = true)
            currentOverlay = null

            // 4. 앱 정보 초기화
            currentBlockedPackage = null
            currentBlockedAppName = null

            // 5. 홈 이동 요청이 있으면 지연 후 실행 (영상 재생 중 화면 축소 방지)
            if (shouldGoHome) {
                delay(DELAY_AFTER_OVERLAY_DISMISS_MS)
                navigateToHome("오버레이 종료 요청", blockedPackageForCoolDown)
            }

            // 6. 닫기 완료 후 플래그 해제 (경쟁 조건 방지)
            delay(100) // 추가 안전 지연
            isOverlayDismissing = false
        }
    }

    /**
     * [핵심 수정] 홈 화면 이동 로직을 클래스 멤버 함수로 분리 (공용 사용)
     */
    fun navigateToHome(contextLabel: String, blockedPackageForCoolDown: String? = null) {
        Log.d(TAG, "홈 이동 실행 ($contextLabel)")

        // 쿨다운 설정: 파라미터로 전달된 패키지 정보 우선 사용, 없으면 currentBlockedPackage 확인
        val packageForCoolDown = blockedPackageForCoolDown ?: currentBlockedPackage
        if (packageForCoolDown != null) {
            lastHomeNavigationPackage = packageForCoolDown
            lastHomeNavigationTime = System.currentTimeMillis()
            Log.d(TAG, "쿨다운 설정: $packageForCoolDown (${COOLDOWN_DURATION_MS}ms)")
        }

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

    /**
     * [상태전이 시스템] 상태 전이 로직
     * ALLOWED → BLOCKED 전이 시에만 오버레이 표시 (중복 방지)
     */
    private fun transitionToState(newState: MiningState, packageName: String, triggerOverlay: Boolean) {
        val previousState = currentMiningState
        
        // 상태 변경이 없으면 아무것도 하지 않음 (중복 오버레이 방지)
        if (previousState == newState) {
            Log.d(TAG, "상태 전이 스킵: $previousState → $newState (변경 없음)")
            return
        }
        
        Log.d(TAG, "상태 전이: $previousState → $newState ($packageName)")
        currentMiningState = newState
        
        when (newState) {
            MiningState.ALLOWED -> {
                // 화면 OFF 시 차단 앱 오디오 재생 중이었는지 확인
                val wasAudioBlockedOnScreenOff = preferenceManager.wasAudioBlockedOnScreenOff()
                if (wasAudioBlockedOnScreenOff) {
                    Log.d(TAG, "화면 OFF 시 차단 앱 오디오 재생 기록 존재: 채굴 재개하지 않음")
                    // 플래그는 오디오 종료 시에만 리셋됨 (PointMiningService에서 처리)
                    return
                }
                
                PointMiningService.resumeMining()
                Log.d(TAG, "Mining Resumed: 허용 앱으로 전환")
                preferenceManager.setLastMiningApp(packageName)
                lastAllowedPackage = null
                hideOverlay(shouldGoHome = false)
            }
            MiningState.BLOCKED -> {
                PointMiningService.pauseMining()
                Log.d(TAG, "Mining Paused: 차단 앱 감지 ($packageName)")
                preferenceManager.setLastMiningApp(packageName)
                
                // Grace Period 체크
                if (packageName == lastAllowedPackage) {
                    Log.d(TAG, "Grace Period: 오버레이 표시 안 함")
                    return
                }
                
                // 오버레이는 ALLOWED → BLOCKED 전이 시에만 즉시 표시
                if (triggerOverlay && previousState == MiningState.ALLOWED) {
                    serviceScope.launch {
                        val appName = getAppName(packageName)
                        showOverlay(packageName, appName)
                    }
                }
            }
        }
    }

    /**
     * [상태전이 시스템] 오디오 상태 변경 처리
     * PointMiningService에서 오디오 상태 변경 시 호출됨
     */
    fun onAudioBlockStateChanged(isBlocked: Boolean) {
        if (isBlocked) {
            // 오디오 차단 감지: ALLOWED → BLOCKED 전이
            transitionToState(MiningState.BLOCKED, "audio", triggerOverlay = false)
        } else {
            // 오디오 종료: BLOCKED → ALLOWED 전이
            transitionToState(MiningState.ALLOWED, "audio", triggerOverlay = false)
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
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    // 화면 OFF 시 차단 앱 오디오 재생 상태 확인 및 저장
                    // 오디오 상태 변경 콜백이 이미 호출되어 isPausedByAudio 상태가 업데이트되었을 수 있음
                    Log.d(TAG, "[화면 OFF] 차단 앱 오디오 재생 상태 확인 시작")
                    val isPausedByAudio = PointMiningService.isPausedByAudio()
                    Log.d(TAG, "[화면 OFF] 오디오 상태 확인 결과: isPausedByAudio=$isPausedByAudio")
                    preferenceManager.setAudioBlockedOnScreenOff(isPausedByAudio)
                    if (isPausedByAudio) {
                        Log.d(TAG, "[화면 OFF] 차단 앱 오디오 재생 중: 채굴 중지 상태 기록")
                    } else {
                        Log.d(TAG, "[화면 OFF] 차단 앱 오디오 재생 중 아님: 상태 기록 (false)")
                    }

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
                        navigateToHome("차단 상태", null)
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