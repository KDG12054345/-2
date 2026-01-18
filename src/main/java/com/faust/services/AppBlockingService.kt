package com.faust.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import com.faust.presentation.view.OverlayDismissCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.BufferOverflow
import java.util.concurrent.ConcurrentHashMap

/**
 * 앱 실행 이벤트 데이터 클래스
 */
data class AppLaunchEvent(
    val windowId: Int,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis()
)

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
    @Volatile
    private var currentOverlay: GuiltyNegotiationOverlay? = null

    // 차단된 앱 목록을 메모리에 캐싱
    private val blockedAppsCache = ConcurrentHashMap.newKeySet<String>()

    // 홈 런처 패키지 목록 (CATEGORY_HOME Intent를 처리할 수 있는 앱들)
    private val homeLauncherPackages = ConcurrentHashMap.newKeySet<String>()

    // 페널티를 지불한 앱을 기억하는 변수 (Grace Period)
    @Volatile
    private var lastAllowedPackage: String? = null

    // PenaltyService 인스턴스
    private val penaltyService: PenaltyService by lazy {
        PenaltyService(this)
    }

    // 현재 협상 중인 앱 정보 저장용
    @Volatile
    private var currentBlockedPackage: String? = null
    @Volatile
    private var currentBlockedAppName: String? = null

    // 화면 OFF 감지용 BroadcastReceiver
    private var screenOffReceiver: BroadcastReceiver? = null

    // 오버레이 상태 머신 (Overlay State Machine)
    enum class OverlayState {
        IDLE,           // 대기 상태 (오버레이 없음)
        SHOWING,        // 표시 중 (오버레이 생성 중)
        DISMISSING      // 닫히는 중 (오버레이 제거 중)
    }
    
    @Volatile
    private var overlayState: OverlayState = OverlayState.IDLE

    // 쿨다운 메커니즘 (중복 유죄협상 방지)
    @Volatile
    private var lastHomeNavigationPackage: String? = null
    @Volatile
    private var lastHomeNavigationTime: Long = 0L
    private val COOLDOWN_DURATION_MS = 1000L // 1초
    private val DELAY_AFTER_OVERLAY_DISMISS_MS = 150L // 오버레이 닫은 후 홈 이동 지연 시간
    private val DELAY_AFTER_PERSONA_AUDIO_STOP_MS = 150L // PersonaEngine 오디오 정지 완료 대기 시간 (오디오 콜백 지연 고려)
    private val HOME_LAUNCHER_DETECTION_TIMEOUT_MS = 500L // 홈 런처 감지 타임아웃 (백업 메커니즘)

    // Window ID 기반 중복 호출 방지 메커니즘
    @Volatile
    private var lastWindowId: Int = -1
    @Volatile
    private var lastProcessedPackage: String? = null
    private val THROTTLE_DELAY_MS = 300L // Throttling 지연 시간 (300ms)
    
    // Flow 기반 이벤트 처리 인프라
    private val appLaunchEvents = MutableSharedFlow<AppLaunchEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var appLaunchFlowJob: Job? = null
    
    // 현재 활성 앱 추적 (정합성 체크용)
    @Volatile
    private var latestActivePackage: String? = null

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

        // 오버레이 표시 상태 추적 (PersonaEngine 오디오 재생 제외용)
        @Volatile
        private var isOverlayActive: Boolean = false

        /**
         * 오버레이가 표시 중인지 확인합니다.
         * PointMiningService에서 PersonaEngine의 오디오 재생을 제외하기 위해 사용됩니다.
         */
        fun isOverlayActive(): Boolean {
            return isOverlayActive
        }

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
        initializeHomeLauncherPackages()
        registerScreenOffReceiver()
        // 상태전이 시스템: PointMiningService에 콜백 등록
        PointMiningService.setBlockingServiceCallback(this)
        // Flow 기반 이벤트 수집 시작
        startAppLaunchFlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        blockedAppsFlowJob?.cancel()
        appLaunchFlowJob?.cancel()
        serviceScope.cancel()
        hideOverlay(shouldGoHome = false)
        blockedAppsCache.clear()
        homeLauncherPackages.clear()
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

    /**
     * 홈 런처 패키지 목록 초기화
     * CATEGORY_HOME Intent를 처리할 수 있는 모든 앱을 찾아서 저장
     */
    private fun initializeHomeLauncherPackages() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfos: List<ResolveInfo> = packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            homeLauncherPackages.clear()
            homeLauncherPackages.addAll(resolveInfos.mapNotNull { it.activityInfo?.packageName })
            Log.d(TAG, "홈 런처 패키지 초기화 완료: ${homeLauncherPackages.size}개 (${homeLauncherPackages.joinToString()})")
        } catch (e: Exception) {
            Log.e(TAG, "홈 런처 패키지 초기화 실패", e)
            homeLauncherPackages.clear()
        }
    }
    
    /**
     * Flow 기반 앱 실행 이벤트 수집 시작
     */
    private fun startAppLaunchFlow() {
        appLaunchFlowJob?.cancel()
        appLaunchFlowJob = serviceScope.launch {
            appLaunchEvents
                .debounce(THROTTLE_DELAY_MS)
                .catch { e -> 
                    // 에러 핸들링: 스트림이 끊기지 않도록 로그만 남기고 계속 진행
                    Log.e(TAG, "Flow 수집 중 예외 발생", e)
                }
                .collectLatest { event ->
                    // collectLatest 사용: 이전 작업 취소하고 최신 이벤트만 처리
                    // 빠른 앱 전환 시나리오에서 반응성 향상
                    
                    // ===== 정합성 체크 1: overlayState 체크 (distinctUntilChanged 대체) =====
                    if (overlayState != OverlayState.IDLE) {
                        Log.d(TAG, "오버레이 활성 상태: 무시 (event=$event, overlayState=$overlayState)")
                        return@collectLatest
                    }
                    
                    // ===== 정합성 체크 2: latestActivePackage 불일치 체크 =====
                    // debounce 지연 중 사용자가 다른 앱으로 이동했다가 돌아온 경우 대응
                    // 단, 홈 런처에서 다른 앱으로 전환하는 경우는 허용 (빠른 앱 실행 시나리오 대응)
                    val isFromHomeLauncher = latestActivePackage != null && latestActivePackage in homeLauncherPackages
                    if (latestActivePackage != event.packageName && !isFromHomeLauncher) {
                        Log.d(TAG, "패키지 불일치: 무시 (expected=$latestActivePackage, actual=${event.packageName})")
                        return@collectLatest
                    }
                    
                    // ===== 정합성 체크 3: currentOverlay null 체크 =====
                    if (currentOverlay != null) {
                        Log.d(TAG, "오버레이 이미 표시 중: 무시 (currentOverlay=$currentOverlay)")
                        return@collectLatest
                    }
                    
                    // ===== 모든 체크 통과: handleAppLaunch 호출 =====
                    handleAppLaunch(event.packageName)
                    
                    // ===== 실제 처리 후: lastProcessedPackage 업데이트 =====
                    // ⚠️ 중요: 실제 처리 후에만 업데이트 (다음 Window ID 검사용)
                    lastProcessedPackage = event.packageName
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val windowId = event.windowId
            val packageName = event.packageName?.toString()
            val className = event.className?.toString()
            
            // ===== 필터링 1: IGNORED_PACKAGES 체크 (최우선) =====
            if (packageName != null && packageName in IGNORED_PACKAGES) {
                Log.d(TAG, "IGNORED_PACKAGES: 무시 (package=$packageName)")
                return  // Flow로 보내지 않음
            }
            
            // ===== 필터링 1.5: 오버레이 패키지의 FrameLayout 이벤트 필터링 =====
            // 오버레이가 표시된 후 FrameLayout 이벤트가 Flow로 전송되어 불필요한 체크 발생 방지
            if (packageName == "com.faust" && className != null && className.contains("FrameLayout")) {
                Log.d(TAG, "오버레이 FrameLayout 이벤트: 무시 (package=$packageName, className=$className)")
                return  // Flow로 보내지 않음
            }
            
            // ===== 필터링 2: className 필터링 (Layout/View 제외 - 무한 디바운스 방지 핵심) =====
            // ⚠️ 중요: Layout/View를 제외하여 노이즈 이벤트를 사전 차단
            // Layout/View는 매우 빈번하게 발생하여 debounce 타이머를 계속 리셋시킬 수 있음
            // className이 null인 경우는 보수적으로 허용 (일부 시스템 이벤트 처리)
            // 홈 런처 패키지는 className 필터링을 우회 (홈 화면 감지 보장)
            val isHomeLauncher = packageName != null && packageName in homeLauncherPackages
            val isValidClass = isHomeLauncher || className == null || (
                className.contains("Activity") ||
                className.contains("Dialog") ||
                className.contains("Fragment")
            )
            
            // Layout, ViewGroup, View 등은 제외 (무한 디바운스 방지)
            // 단, 홈 런처는 예외로 허용 (홈 화면 감지 보장)
            if (!isValidClass) {
                Log.d(TAG, "Activity/Dialog/Fragment 아님 (Layout/View 제외): 무시 (className=$className, package=$packageName)")
                return  // Flow로 보내지 않음
            }
            
            // ===== 필터링 3: Window ID 검사 (중복 이벤트 사전 차단) =====
            // 오버레이가 닫힌 후(IDLE)에는 같은 앱 재실행 허용 (반복 실행 시나리오 대응)
            // 기존 방어막(Grace Period, Cool-down)이 handleAppLaunch()에서 작동하여 중복 호출 방지
            if (windowId != -1) {
                // Window ID가 유효한 경우: 같은 창이면 무시
                // 단, 오버레이가 닫힌 후(IDLE)에는 같은 앱 재실행 허용
                if (windowId == lastWindowId && packageName == lastProcessedPackage && overlayState != OverlayState.IDLE) {
                    Log.d(TAG, "Window ID 중복: 무시 (windowId=$windowId, package=$packageName, overlayState=$overlayState)")
                    return  // Flow로 보내지 않음
                }
            } else {
                // Window ID가 -1인 경우: 오버레이 상태 확인
                if (overlayState != OverlayState.IDLE && packageName == lastProcessedPackage) {
                    Log.d(TAG, "Window ID -1 중복: 무시 (package=$packageName, overlayState=$overlayState)")
                    return  // Flow로 보내지 않음
                }
            }
            
            // ===== 필터링 4: 오버레이 상태 체크 =====
            if (overlayState != OverlayState.IDLE) {
                Log.d(TAG, "오버레이 활성 상태: 무시 (overlayState=$overlayState)")
                return  // Flow로 보내지 않음
            }
            
            // ===== 모든 필터링 통과: Flow로 이벤트 전송 =====
            if (packageName != null) {
                // latestActivePackage 업데이트 (Flow로 보내기 전)
                // ⚠️ 중요: 이 값은 "현재 활성 앱"을 추적하며, debounce 후 collectLatest에서
                // 이 값과 비교하여 사용자가 이미 다른 앱으로 이동했는지 검출함
                // debounce 지연 중 빠른 앱 전환 시나리오에서 정합성 체크에 사용됨
                latestActivePackage = packageName
                
                // Flow로 이벤트 전송
                // ⚠️ 중요: onAccessibilityEvent()는 suspend 함수가 아니므로 tryEmit() 사용
                // emit()은 suspend 함수이므로 코루틴 스코프 내에서만 사용 가능
                val launchEvent = AppLaunchEvent(
                    windowId = windowId,
                    packageName = packageName,
                    timestamp = System.currentTimeMillis()
                )
                
                // tryEmit()은 즉시 반환되며, 버퍼가 가득 차면 false 반환 (DROP_OLDEST 정책으로 자동 처리)
                if (!appLaunchEvents.tryEmit(launchEvent)) {
                    Log.w(TAG, "Flow 버퍼 가득 참: 이벤트 유실 (package=$packageName)")
                }
                
                // lastWindowId 업데이트 (다음 이벤트 검사용)
                // ⚠️ 주의: lastProcessedPackage는 collectLatest 블록에서 실제 처리 후에만 업데이트
                lastWindowId = windowId
            }
        }
    }

    fun setAllowedPackage(packageName: String?) {
        lastAllowedPackage = packageName
    }

    private fun handleAppLaunch(packageName: String) {
        val currentTime = System.currentTimeMillis()

        // 1. 오버레이 상태 체크: 상태 머신 기반 (최우선 체크)
        // - currentOverlay != null: 오버레이가 이미 표시 중
        // - overlayState != IDLE: 오버레이가 표시 중이거나 닫히는 중
        if (currentOverlay != null || overlayState != OverlayState.IDLE) {
            Log.d(TAG, "오버레이 활성 상태: 패키지 변경 무시 ($packageName, currentOverlay=${currentOverlay != null}, overlayState=$overlayState)")
            return
        }

        // 2. 무시할 패키지 체크
        if (packageName in IGNORED_PACKAGES) return

        // 3. 홈 런처 감지: 홈 화면으로 이동한 경우 상태를 ALLOWED로 전이
        if (packageName in homeLauncherPackages) {
            Log.d(TAG, "홈 런처 감지: 상태를 ALLOWED로 전이 ($packageName)")
            transitionToState(MiningState.ALLOWED, packageName, triggerOverlay = false)
            return
        }

        // 4. 차단 앱 여부 확인
        val isBlocked = blockedAppsCache.contains(packageName)

        if (isBlocked) {
            // 5. Grace Period 체크: 강행 버튼을 눌러 페널티를 지불한 앱은 중복 징벌 방지
            if (packageName == lastAllowedPackage) {
                Log.d(TAG, "Grace Period 활성: 중복 징벌 방지 - 오버레이 표시 차단 ($packageName)")
            // Grace Period가 활성화된 경우에도 채굴은 중단해야 함 (상태 전이는 수행)
            // 하지만 오버레이는 표시하지 않음
            transitionToState(MiningState.BLOCKED, packageName, triggerOverlay = false)
            return
            }
            
            // 6. 쿨다운 체크: 같은 앱이 최근에 홈으로 이동했고 쿨다운 시간 내면 오버레이 표시 차단
            if (packageName == lastHomeNavigationPackage && 
                (currentTime - lastHomeNavigationTime) < COOLDOWN_DURATION_MS) {
                val elapsedTime = currentTime - lastHomeNavigationTime
                Log.d(TAG, "Cool-down 활성: 오버레이 표시 차단 ($packageName, 경과 시간=${elapsedTime}ms, 쿨다운=${COOLDOWN_DURATION_MS}ms)")
                return
            } else if (packageName == lastHomeNavigationPackage) {
                val elapsedTime = currentTime - lastHomeNavigationTime
                Log.d(TAG, "Cool-down 만료: 오버레이 표시 허용 ($packageName, 경과 시간=${elapsedTime}ms, 쿨다운=${COOLDOWN_DURATION_MS}ms)")
            }

            // 7. 중복 호출 방지: Window ID + Throttling이 주 방어선이므로 여기서는 보조 체크만
            // (Window ID 검사와 Throttling으로 대부분 차단되지만, 추가 안전장치로 유지)
            // 주의: 이 로직은 Window ID 검사와 Throttling 이후에 실행되므로 거의 실행되지 않음

            // 8. 상태전이 시스템: ALLOWED → BLOCKED 전이
            transitionToState(MiningState.BLOCKED, packageName, triggerOverlay = true)
        } else {
            // 9. 상태전이 시스템: BLOCKED → ALLOWED 전이
            // 허용 앱은 Window ID + Throttling으로 충분히 필터링됨
            transitionToState(MiningState.ALLOWED, packageName, triggerOverlay = false)
        }
    }

    override fun onInterrupt() {
        hideOverlay(shouldGoHome = false)
    }

    private fun showOverlay(packageName: String, appName: String) {
        // 상태 머신 체크: IDLE 상태가 아니면 차단
        if (overlayState != OverlayState.IDLE) {
            Log.d(TAG, "오버레이 생성 차단: 현재 상태=$overlayState")
            return
        }
        
        // 동기 체크: 오버레이가 이미 있으면 차단
        if (currentOverlay != null) {
            Log.d(TAG, "오버레이 생성 차단: currentOverlay != null")
            return
        }

        // 상태 전이: IDLE → SHOWING
        overlayState = OverlayState.SHOWING
        this.currentBlockedPackage = packageName
        this.currentBlockedAppName = appName

        serviceScope.launch(Dispatchers.Main) {
            try {
                // 비동기 이중 체크: 경쟁 조건 방지
                if (overlayState == OverlayState.SHOWING && currentOverlay == null) {
                    currentOverlay = GuiltyNegotiationOverlay(
                        this@AppBlockingService,
                        object : OverlayDismissCallback {
                            override fun onDismissed() {
                                // 오버레이 닫힘 완료 시점 명확화
                                // 상태는 hideOverlay()에서 관리하므로 여기서는 로깅만
                                Log.d(TAG, "오버레이 닫힘 콜백 수신")
                            }
                        }
                    ).apply {
                        show(packageName, appName)
                    }
                    // 오버레이 표시 상태 설정
                    isOverlayActive = true
                    // 상태 전이: SHOWING → IDLE (표시 완료)
                    overlayState = OverlayState.IDLE
                    Log.d(TAG, "오버레이 표시 완료: 상태=IDLE")
                } else {
                    Log.d(TAG, "오버레이 생성 차단 (비동기 체크): overlayState=$overlayState, currentOverlay=${currentOverlay != null}")
                    // 상태 복구
                    if (overlayState == OverlayState.SHOWING) {
                        overlayState = OverlayState.IDLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "오버레이 생성 실패", e)
                // 예외 발생 시 상태 복구
                overlayState = OverlayState.IDLE
            }
        }
    }

    /**
     * [핵심 수정] 오버레이를 닫고, 필요 시 홈으로 이동시킵니다.
     * 외부(Overlay)에서 호출 가능하도록 public으로 변경되었습니다.
     * 
     * @param shouldGoHome 홈으로 이동할지 여부
     * @param applyCooldown 쿨다운 적용 여부 (기본값: true, 철회 버튼 클릭 시 false)
     */
    fun hideOverlay(shouldGoHome: Boolean = false, applyCooldown: Boolean = true) {
        // 1. 참조 백업 (dismiss 호출용 - 비동기 블록에서 사용)
        val overlayToDismiss = currentOverlay
        
        // 2. 패키지 정보 백업 (쿨다운 설정용)
        val blockedPackageForCoolDown = currentBlockedPackage

        // 3. 즉시 상태 동기화 및 리셋 (경쟁 조건 방지 핵심)
        // - currentOverlay = null: handleAppLaunch()에서 즉시 새 오버레이 생성 가능하도록
        // - overlayState = IDLE: 즉시 IDLE로 전환하여 재진입 허용 (비동기 작업 완료 대기하지 않음)
        // - Window ID 기억 리셋: 즉시 리셋하여 재진입 허용
        currentOverlay = null
        overlayState = OverlayState.IDLE
        lastWindowId = -1
        lastProcessedPackage = null

        // 4. 앱 정보 초기화
        currentBlockedPackage = null
        currentBlockedAppName = null

        serviceScope.launch(Dispatchers.Main) {
            try {
                // 5. 백업한 참조로 오버레이 닫기 (리소스 정리만 수행)
                // 상태는 이미 IDLE로 전환되었으므로 리소스 정리만 수행
                overlayToDismiss?.dismiss(force = true)  // personaEngine.stopAll() 호출

                // 6. PersonaEngine 오디오 정지 완료 대기 (오디오 콜백 지연 고려)
                // MediaPlayer 정지 및 시스템 오디오 콜백 지연을 고려한 안전 지연
                delay(DELAY_AFTER_PERSONA_AUDIO_STOP_MS)

                // 7. 오버레이 표시 상태 해제 (PersonaEngine 오디오 정지 완료 후)
                isOverlayActive = false
                Log.d(TAG, "오버레이 표시 상태 해제: false (PersonaEngine 오디오 정지 완료 후)")

                // 8. 홈 이동 요청이 있으면 지연 후 실행 (영상 재생 중 화면 축소 방지)
                if (shouldGoHome) {
                    delay(DELAY_AFTER_OVERLAY_DISMISS_MS)
                    navigateToHome("오버레이 종료 요청", blockedPackageForCoolDown, applyCooldown)
                    
                    // 홈 이동 후 상태 전이 보장 메커니즘 (백업)
                    // 홈 런처 이벤트가 발생하지 않거나 지연되는 경우를 대비한 타임아웃 기반 전이
                    delay(HOME_LAUNCHER_DETECTION_TIMEOUT_MS)
                    
                    // 홈 런처가 감지되지 않았으면 강제로 ALLOWED 상태로 전이
                    // 이렇게 하면 다시 차단 앱 실행 시 정상적으로 유죄협상 진행됨
                    if (currentMiningState == MiningState.BLOCKED) {
                        Log.w(TAG, "홈 런처 감지 타임아웃: 강제로 ALLOWED 상태로 전이 (다음 실행 시 유죄협상 보장)")
                        transitionToState(MiningState.ALLOWED, "home", triggerOverlay = false)
                    } else {
                        Log.d(TAG, "홈 런처 감지 완료 또는 이미 ALLOWED 상태: 상태 전이 불필요 (현재 상태: $currentMiningState)")
                    }
                }
                
                // 상태는 이미 IDLE이므로 추가 전이 불필요
                Log.d(TAG, "오버레이 리소스 정리 완료 (상태는 이미 IDLE)")
            } catch (e: Exception) {
                Log.e(TAG, "오버레이 닫기 실패", e)
                // 상태는 이미 IDLE이므로 복구 불필요
            }
        }
    }

    /**
     * [핵심 수정] 홈 화면 이동 로직을 클래스 멤버 함수로 분리 (공용 사용)
     * 
     * @param contextLabel 홈 이동 실행 컨텍스트 (로깅용)
     * @param blockedPackageForCoolDown 쿨다운 적용할 패키지명 (null이면 currentBlockedPackage 사용)
     * @param applyCooldown 쿨다운 적용 여부 (기본값: true, 철회 버튼 클릭 시 false)
     */
    fun navigateToHome(
        contextLabel: String, 
        blockedPackageForCoolDown: String? = null,
        applyCooldown: Boolean = true
    ) {
        Log.d(TAG, "홈 이동 실행 ($contextLabel, applyCooldown=$applyCooldown)")

        // 쿨다운 설정: applyCooldown이 true일 때만 설정
        if (applyCooldown) {
            val packageForCoolDown = blockedPackageForCoolDown ?: currentBlockedPackage
            if (packageForCoolDown != null) {
                lastHomeNavigationPackage = packageForCoolDown
                lastHomeNavigationTime = System.currentTimeMillis()
                Log.d(TAG, "쿨다운 설정: $packageForCoolDown (${COOLDOWN_DURATION_MS}ms)")
            }
        } else {
            // 쿨다운 면제: 철회 버튼 클릭으로 인한 홈 이동
            // 쿨다운 변수 리셋하여 재실행 시 오버레이 표시 보장
            lastHomeNavigationPackage = null
            lastHomeNavigationTime = 0L
            Log.d(TAG, "쿨다운 면제: 철회 버튼 클릭으로 인한 홈 이동 (쿨다운 변수 리셋)")
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
     * [수정 2026-01-18] 상태가 같아도 오버레이가 없고 조건을 만족하면 오버레이 표시
     */
    private fun transitionToState(newState: MiningState, packageName: String, triggerOverlay: Boolean) {
        val previousState = currentMiningState
        
        // 상태 변경이 없으면 채굴 상태만 업데이트하고 오버레이 체크는 별도로 수행
        val isStateChanged = previousState != newState
        
        if (isStateChanged) {
            Log.d(TAG, "상태 전이: $previousState → $newState ($packageName)")
            currentMiningState = newState
        } else {
            Log.d(TAG, "상태 전이 스킵: $previousState → $newState (변경 없음)")
        }
        
        when (newState) {
            MiningState.ALLOWED -> {
                if (!isStateChanged) {
                    // 상태 변경이 없으면 ALLOWED 처리 스킵
                    return
                }
                
                // 화면 OFF 시 차단 앱 오디오 재생 중이었는지 확인
                val wasAudioBlockedOnScreenOff = preferenceManager.wasAudioBlockedOnScreenOff()
                if (wasAudioBlockedOnScreenOff) {
                    Log.d(TAG, "화면 OFF 시 차단 앱 오디오 재생 기록 존재: 채굴 재개하지 않음")
                    // 플래그는 오디오 종료 시에만 리셋됨 (PointMiningService에서 처리)
                    return
                }
                
                Log.d(TAG, "[상태 전이] ALLOWED → resumeMining() 호출")
                PointMiningService.resumeMining()
                Log.d(TAG, "Mining Resumed: 허용 앱으로 전환")
                preferenceManager.setLastMiningApp(packageName)
                lastAllowedPackage = null
                hideOverlay(shouldGoHome = false)
            }
            MiningState.BLOCKED -> {
                // 상태 변경이 있으면 채굴 중단 처리
                if (isStateChanged) {
                    Log.d(TAG, "[상태 전이] BLOCKED → pauseMining() 호출")
                    PointMiningService.pauseMining()
                    Log.d(TAG, "Mining Paused: 차단 앱 감지 ($packageName)")
                    preferenceManager.setLastMiningApp(packageName)
                }
                
                // Grace Period 체크
                if (packageName == lastAllowedPackage) {
                    Log.d(TAG, "Grace Period: 오버레이 표시 안 함")
                    return
                }
                
                // 오버레이 표시 조건:
                // 1. triggerOverlay가 true이고
                // 2. ALLOWED → BLOCKED 전이인 경우만 오버레이 표시
                //    (상태 변경이 없으면 이미 처리된 상태이므로 오버레이 표시하지 않음 - hideOverlay() 직후 중복 표시 방지)
                // 3. 오버레이가 현재 표시되지 않은 경우
                val shouldShowOverlay = triggerOverlay && 
                    isStateChanged && previousState == MiningState.ALLOWED &&  // 상태 변경이 있어야 함
                    currentOverlay == null && overlayState == OverlayState.IDLE
                
                if (shouldShowOverlay) {
                    Log.d(TAG, "오버레이 표시 조건 충족: triggerOverlay=$triggerOverlay, isStateChanged=$isStateChanged, previousState=$previousState, currentOverlay=${currentOverlay != null}")
                    serviceScope.launch {
                        val appName = getAppName(packageName)
                        showOverlay(packageName, appName)
                    }
                } else {
                    Log.d(TAG, "오버레이 표시 조건 불충족: triggerOverlay=$triggerOverlay, isStateChanged=$isStateChanged, previousState=$previousState, currentOverlay=${currentOverlay != null}, overlayState=$overlayState")
                }
            }
        }
    }

    /**
     * [상태전이 시스템] 오디오 상태 변경 처리
     * PointMiningService에서 오디오 상태 변경 시 호출됨
     */
    fun onAudioBlockStateChanged(isBlocked: Boolean) {
        Log.d(TAG, "[오디오 상태 변경] isBlocked=$isBlocked")
        if (isBlocked) {
            // 오디오 차단 감지: ALLOWED → BLOCKED 전이
            Log.d(TAG, "[오디오 상태 변경] 차단 감지 → 채굴 중단 처리")
            transitionToState(MiningState.BLOCKED, "audio", triggerOverlay = false)
        } else {
            // 오디오 종료: BLOCKED → ALLOWED 전이
            Log.d(TAG, "[오디오 상태 변경] 차단 해제 → 채굴 재개 처리")
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
                    // Case 2: 오버레이 없이 차단 상태 -> 화면 OFF 시 홈 이동 제거 (화면 깜빡임 방지)
                    // 화면이 꺼진 상태에서는 사용자가 앱을 볼 수 없으므로 홈 이동 불필요
                    // 화면 ON 시 차단 앱이 보이면 자연스럽게 오버레이가 표시됨
                    else if (PointMiningService.isMiningPaused() && currentMiningState == MiningState.BLOCKED) {
                        Log.d(TAG, "차단 상태(오버레이 없음)에서 화면 OFF: 홈 이동 스킵 (화면 깜빡임 방지)")
                    } else if (PointMiningService.isMiningPaused() && currentMiningState == MiningState.ALLOWED) {
                        Log.d(TAG, "차단 상태(오버레이 없음)이지만 이미 ALLOWED 상태(홈 화면): 홈 이동 스킵")
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