package com.faust.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.faust.FaustApplication
import com.faust.R
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.domain.AppGroupService
import com.faust.domain.PenaltyService
import com.faust.domain.StrictModeService
import com.faust.domain.TimeCreditService
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

    /** 하이브리드 오디오: 200ms 지연 재검사용 (OS 미디어 세션 정리 대기) */
    private val mainHandler = Handler(Looper.getMainLooper())

    // TimeCreditService 인스턴스
    private val timeCreditService: TimeCreditService by lazy {
        TimeCreditService(this)
    }

    // AppGroupService 인스턴스
    private val appGroupService: AppGroupService by lazy {
        AppGroupService(this)
    }

    // 현재 협상 중인 앱 정보 저장용
    @Volatile
    private var currentBlockedPackage: String? = null
    @Volatile
    private var currentBlockedAppName: String? = null

    // 화면 OFF 감지용 BroadcastReceiver
    private var screenOffReceiver: BroadcastReceiver? = null

    /**
     * 오버레이 생명주기 상태 머신 (단일 상태 소스).
     *
     * 상태 전이:
     *   IDLE -> SHOWING -> ACTIVE -> DISMISSING -> IDLE
     *
     * - IDLE: 오버레이 없음. 새 오버레이 생성 가능. 이벤트 처리 허용.
     * - SHOWING: 오버레이 비동기 생성 중. 이벤트 차단.
     * - ACTIVE: 오버레이가 화면에 표시 중. 이벤트 차단. PersonaEngine 오디오 재생 중.
     * - DISMISSING: 오버레이 제거 중 + PersonaEngine 오디오 정지 대기(150ms).
     *              이벤트 허용 (빠른 재진입). 오디오 검사 차단 (PersonaEngine 오감지 방지).
     *
     * 이벤트 가드: [isOverlayBlockingEvents] (SHOWING || ACTIVE)
     * 오디오 가드: [isPersonaAudioPossiblyPlaying] (ACTIVE || DISMISSING)
     */
    enum class OverlayState {
        IDLE,
        SHOWING,
        ACTIVE,
        DISMISSING
    }

    /**
     * 오버레이 생명주기 상태.
     * companion의 _overlayState에 위임. 단일 변수이므로 상태 불일치 불가.
     */
    private var overlayState: OverlayState
        get() = AppBlockingService.getOverlayState()
        set(value) { AppBlockingService.setOverlayState(value) }

    /**
     * 이벤트 처리를 차단해야 하는 상태인지 확인.
     * SHOWING(생성 중), ACTIVE(표시 중)일 때 이벤트 차단.
     * DISMISSING은 차단하지 않음 (빠른 재진입 허용).
     */
    private fun isOverlayBlockingEvents(): Boolean =
        overlayState == OverlayState.SHOWING || overlayState == OverlayState.ACTIVE

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
    
    // 엄격모드 설정 페이지 차단 쿨다운 (성능 최적화)
    @Volatile
    private var lastStrictModeCheckTime: Long = 0L
    private val STRICT_MODE_CHECK_COOLDOWN_MS = 200L // 200ms 쿨다운
    private val STRICT_MODE_RETRY_DELAY_MS = 150L // rootInActiveWindow null일 때 재시도 지연 시간
    
    // 엄격모드 토스트 중복 방지
    @Volatile
    private var isStrictModeToastShowing: Boolean = false
    
    // 엄격모드 재시도 코루틴 Job (취소 처리용)
    private var strictModeRetryJob: Job? = null
    
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
        ALLOWED,  // 절제 시간 누적 활성화
        BLOCKED   // 절제 시간 누적 중단
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

        // 엄격모드 설정 페이지 차단 관련 상수
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val APP_NAME_KR = "파우스트"
        private const val APP_NAME_EN = "Faust"
        private val BLOCKED_KEYWORDS = listOf("제거", "중지", "삭제", "Uninstall", "Disable", "Remove")

        /**
         * 접근성 노드 트리 최대 탐색 깊이.
         * WebView, RecyclerView 등 복잡한 트리에서 StackOverflowError를 사전 방어.
         * 일반적 앱 UI는 20-30 depth 이내이므로 50은 충분한 여유.
         */
        private const val MAX_SEARCH_DEPTH = 50

        /**
         * 오버레이 생명주기 상태 (단일 상태 소스).
         * 인스턴스와 companion 모두 이 변수를 사용하므로 동기화 문제 없음.
         * @Volatile: 멀티스레드 가시성 보장 (Main -> Binder/IO 스레드).
         */
        @Volatile
        private var _overlayState: OverlayState = OverlayState.IDLE

        /**
         * PersonaEngine 오디오가 아직 재생 중일 수 있는 상태인지 확인.
         * 기존 isOverlayActive()를 대체. 외부(TimeCreditBackgroundService)에서 호출.
         */
        fun isPersonaAudioPossiblyPlaying(): Boolean =
            _overlayState == OverlayState.ACTIVE || _overlayState == OverlayState.DISMISSING

        internal fun getOverlayState(): OverlayState = _overlayState
        internal fun setOverlayState(value: OverlayState) {
            _overlayState = value
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
        // 상태전이 시스템: TimeCreditBackgroundService에 콜백 등록
        TimeCreditBackgroundService.setBlockingServiceCallback(this)
        TimeCreditBackgroundService.setCreditExhaustedCallback { packageName -> onCreditExhausted(packageName) }
        TimeCreditBackgroundService.setScreenOnSettlementDoneCallback { onScreenOnSettlementDone() }
        // 서비스 생존 보장: TimeCreditBackgroundService가 없으면 재기동
        if (!TimeCreditBackgroundService.isServiceRunning()) {
            TimeCreditBackgroundService.startService(applicationContext)
        }
        // Flow 기반 이벤트 수집 시작
        startAppLaunchFlow()
    }

    override fun onDestroy() {
        // [버그 수정] 오버레이 동기적 정리: serviceScope 취소 전에 수행
        currentOverlay?.let { overlay ->
            try {
                overlay.dismiss(force = true)
            } catch (e: Exception) {
                Log.e(TAG, "onDestroy 오버레이 동기 정리 실패", e)
            }
        }
        currentOverlay = null
        overlayState = OverlayState.IDLE

        blockedAppsFlowJob?.cancel()
        appLaunchFlowJob?.cancel()
        strictModeRetryJob?.cancel()
        serviceScope.cancel()
        blockedAppsCache.clear()
        homeLauncherPackages.clear()
        unregisterScreenOffReceiver()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        super.onDestroy()
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
                    
                    // ===== 서비스 생존: TimeCreditBackgroundService 없으면 재기동 =====
                    if (!TimeCreditBackgroundService.isServiceRunning()) {
                        TimeCreditBackgroundService.startService(applicationContext)
                    }
                    // ===== 정합성 체크 1: overlayState 체크 (distinctUntilChanged 대체) =====
                    if (isOverlayBlockingEvents()) {
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
                    handleAppLaunch(event.packageName) // suspend 함수로 변경됨
                    
                    // ===== 실제 처리 후: lastProcessedPackage 업데이트 =====
                    // ⚠️ 중요: 실제 처리 후에만 업데이트 (다음 Window ID 검사용)
                    lastProcessedPackage = event.packageName
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        
        // ===== 엄격모드: 설정 페이지 차단 로직 (최우선 처리) =====
        // 엄격모드가 활성화되어 있고 설정 앱에서 앱 정보 페이지가 열렸는지 확인
        // 이벤트 타입 확장: TYPE_VIEW_CLICKED, TYPE_VIEW_FOCUSED 추가
        if (packageName == SETTINGS_PACKAGE && isStrictModeActive()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                
                checkAndBlockSettingsPage()
                return  // 설정 페이지 차단 후 이벤트 처리 중단
            }
        }
        
        // ===== 기존 앱 차단 로직 =====
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val windowId = event.windowId
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
            // Credit Session 앱(차단 앱)은 package 일치 시 클래스 무관하게 '사용 중'으로 인정 (FrameLayout/ViewGroup 포함)
            val isHomeLauncher = packageName != null && packageName in homeLauncherPackages
            val isCreditSessionPackage = packageName != null &&
                preferenceManager.isCreditSessionActive() &&
                packageName == preferenceManager.getCreditSessionPackage()
            val isValidClass = isHomeLauncher || isCreditSessionPackage || className == null || (
                className.contains("Activity") ||
                className.contains("Dialog") ||
                className.contains("Fragment")
            )
            
            // Layout, ViewGroup, View 등은 제외 (무한 디바운스 방지)
            // 단, 홈 런처·Credit Session 패키지는 예외로 허용
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
                if (windowId == lastWindowId && packageName == lastProcessedPackage && isOverlayBlockingEvents()) {
                    Log.d(TAG, "Window ID 중복: 무시 (windowId=$windowId, package=$packageName, overlayState=$overlayState)")
                    return  // Flow로 보내지 않음
                }
            } else {
                // Window ID가 -1인 경우: 오버레이 상태 확인
                if (isOverlayBlockingEvents() && packageName == lastProcessedPackage) {
                    Log.d(TAG, "Window ID -1 중복: 무시 (package=$packageName, overlayState=$overlayState)")
                    return  // Flow로 보내지 않음
                }
            }
            
            // ===== 필터링 4: 오버레이 상태 체크 =====
            if (isOverlayBlockingEvents()) {
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

    private suspend fun handleAppLaunch(packageName: String) {
        val currentTime = System.currentTimeMillis()

        // 1. 오버레이 상태 체크: 상태 머신 기반 (최우선 체크)
        // - currentOverlay != null: 오버레이가 이미 표시 중
        // - overlayState: 오버레이가 표시 중이거나 생성/제거 중
        if (currentOverlay != null || isOverlayBlockingEvents()) {
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

            // 5.5. TimeCredit 체크: 크레딧이 있으면 Credit Session 시작하여 차단 해제
            try {
                if (timeCreditService.isCreditAvailable()) {
                    val sessionResult = timeCreditService.startCreditSession(packageName)
                    if (sessionResult is TimeCreditService.UseResult.Success) {
                        Log.d(TAG, "TimeCredit 사용: Credit Session 시작 ($packageName, 잔액: ${sessionResult.remainingBalanceSeconds}초)")
                        TimeCreditBackgroundService.onCreditSessionStarted()
                        transitionToState(MiningState.ALLOWED, packageName, triggerOverlay = false)
                        return
                    }
                }
                // 크레딧이 없으면 계속 진행하여 유죄협상 오버레이 표시
                Log.d(TAG, "TimeCredit 없음: 유죄협상 실행 ($packageName)")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking time credit for $packageName", e)
                // 에러 발생 시 계속 진행 (오버레이 표시)
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

            // Credit Session: 차단 앱 이탈 시 잔액 있으면 휴면(Dormant) 강제, 없으면 종료.
            // 최종 차감 → 세션 종료 순서. 잔액이 있으면 즉시 종료하지 않고 반드시 휴면을 거침.
            if (preferenceManager.isCreditSessionActive()) {
                val sessionPackage = preferenceManager.getCreditSessionPackage()
                if (sessionPackage != null && sessionPackage != packageName) {
                    val balance = preferenceManager.getTimeCreditBalanceSeconds()
                    if (balance > 0L) {
                        TimeCreditBackgroundService.requestDormant()
                        Log.d(TAG, "Credit Session 휴면 진입: 차단 앱($sessionPackage) 이탈, 잔액 ${balance}초 유지")
                    } else {
                        TimeCreditBackgroundService.performFinalDeduction()
                        timeCreditService.endCreditSession()
                        Log.d(TAG, "Credit Session 종료: 차단 앱($sessionPackage) → 허용 앱($packageName)")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        hideOverlay(shouldGoHome = false)
    }

    private fun showOverlay(packageName: String, appName: String) {
        // 상태 머신 체크: SHOWING/ACTIVE면 차단, DISMISSING은 허용 (빠른 재진입)
        if (isOverlayBlockingEvents()) {
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
                    // [수정] 상태 전이: SHOWING -> ACTIVE (오버레이 표시 중 + PersonaEngine 오디오 재생 중)
                    overlayState = OverlayState.ACTIVE
                    Log.d(TAG, "오버레이 표시 완료: 상태=ACTIVE")
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
        // - ACTIVE -> DISMISSING: 이벤트 허용(재진입 가능), 오디오 검사는 차단 유지
        currentOverlay = null
        overlayState = OverlayState.DISMISSING
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
                delay(DELAY_AFTER_PERSONA_AUDIO_STOP_MS)

                // 7. DISMISSING -> IDLE: PersonaEngine 오디오 완전 정지 후 오디오 검사 재개
                overlayState = OverlayState.IDLE
                Log.d(TAG, "오버레이 상태 전이: DISMISSING -> IDLE (PersonaEngine 오디오 정지 완료)")

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
                    // 플래그는 오디오 종료 시에만 리셋됨 (TimeCreditBackgroundService에서 처리)
                    return
                }
                
                Log.d(TAG, "[상태 전이] ALLOWED → resumeMining() 호출")
                TimeCreditBackgroundService.resumeMining()
                Log.d(TAG, "Mining Resumed: 허용 앱으로 전환")
                preferenceManager.setLastMiningApp(packageName)
                lastAllowedPackage = null
                hideOverlay(shouldGoHome = false)
            }
            MiningState.BLOCKED -> {
                // 상태 변경이 있으면 채굴 중단 처리
                if (isStateChanged) {
                    Log.d(TAG, "[상태 전이] BLOCKED → pauseMining() 호출")
                    TimeCreditBackgroundService.pauseMining()
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
                // 2. ALLOWED -> BLOCKED 전이인 경우만 오버레이 표시
                // 3. 오버레이가 없고 이벤트 차단 중이 아닌 경우 (IDLE 또는 DISMISSING 시 재진입 허용)
                val shouldShowOverlay = triggerOverlay && 
                    isStateChanged && previousState == MiningState.ALLOWED &&
                    currentOverlay == null && !isOverlayBlockingEvents()
                
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
     * [Credit Session 소진] 타임 크레딧 소진 시 즉시 차단 오버레이 표시.
     * TimeCreditBackgroundService의 Exhaustion Timer 또는 최종 차감 시 호출됨.
     */
    fun onCreditExhausted(packageName: String) {
        Log.d(TAG, "[Credit 소진] 즉시 차단 오버레이 표시: $packageName")
        setAllowedPackage(null)
        serviceScope.launch {
            val appName = getAppName(packageName)
            showOverlay(packageName, appName)
        }
    }

    /**
     * [Foreground Re-Entry] Screen ON 후 정산 완료 시 호출.
     * 차단 앱이 포그라운드에 있으면 잔액에 따라: balance <= 0 → Strict Punishment(오버레이), balance > 0 → startCreditSession + 재개.
     */
    private fun onScreenOnSettlementDone() {
        if (currentMiningState != MiningState.BLOCKED || currentBlockedPackage == null) return
        val packageName = currentBlockedPackage!!
        if (packageName !in blockedAppsCache) return
        val balance = preferenceManager.getTimeCreditBalanceSeconds()
        if (balance <= 0L) {
            Log.d(TAG, "[Screen ON 재진입] 잔액 0: Strict Punishment (showGuiltyNegotiationOverlay)")
            serviceScope.launch {
                val appName = currentBlockedAppName ?: getAppName(packageName)
                showOverlay(packageName, appName)
            }
        } else {
            Log.d(TAG, "[Screen ON 재진입] 잔액 있음: 오버레이 스킵, startCreditSession + 재개 ($packageName, balance=${balance}s)")
            try {
                val sessionResult = timeCreditService.startCreditSession(packageName)
                if (sessionResult is TimeCreditService.UseResult.Success) {
                    transitionToState(MiningState.ALLOWED, packageName, triggerOverlay = false)
                } else {
                    serviceScope.launch {
                        val appName = currentBlockedAppName ?: getAppName(packageName)
                        showOverlay(packageName, appName)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startCreditSession 실패 on Screen ON re-entry", e)
                serviceScope.launch {
                    val appName = currentBlockedAppName ?: getAppName(packageName)
                    showOverlay(packageName, appName)
                }
            }
        }
    }

    /**
     * [상태전이 시스템] 오디오 상태 변경 처리
     * TimeCreditBackgroundService에서 오디오 상태 변경 시 호출됨.
     * Credit 세션 중에는 BLOCKED/ALLOWED 전이 모두 무시(플리커 방지·lastMiningApp 무결성).
     * transitionToState에는 항상 실제 패키지명만 전달하여 setLastMiningApp이 유효한 패키지로 유지되도록 함.
     */
    fun onAudioBlockStateChanged(isBlocked: Boolean) {
        Log.d(TAG, "[오디오 상태 변경] isBlocked=$isBlocked")
        if (isBlocked) {
            if (preferenceManager.isCreditSessionActive()) {
                val pkg = preferenceManager.getCreditSessionPackage() ?: ""
                Log.d(TAG, "Audio detected, but Credit Session is active. Keeping ALLOWED state for $pkg")
                return
            }
            val packageForAudio = preferenceManager.getLastMiningApp()
            if (packageForAudio.isNullOrEmpty() || packageForAudio == "audio") {
                Log.d(TAG, "[오디오 상태 변경] BLOCKED 전이 스킵: 유효한 lastMiningApp 없음")
                return
            }
            Log.d(TAG, "[오디오 상태 변경] 차단 감지 → 채굴 중단 처리")
            transitionToState(MiningState.BLOCKED, packageForAudio, triggerOverlay = false)
        } else {
            if (preferenceManager.isCreditSessionActive()) {
                Log.d(TAG, "[오디오 상태 변경] Credit 세션 활성: isBlocked=false 무시 (플리커 방지)")
                return
            }
            val packageForAudio = preferenceManager.getLastMiningApp()
            if (packageForAudio.isNullOrEmpty() || packageForAudio == "audio") {
                Log.d(TAG, "[오디오 상태 변경] ALLOWED 전이 스킵: 유효한 lastMiningApp 없음")
                return
            }
            Log.d(TAG, "[오디오 상태 변경] 차단 해제 → 채굴 재개 처리")
            transitionToState(MiningState.ALLOWED, packageForAudio, triggerOverlay = false)
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
                    // 정밀 타이밍: OS 화면 OFF 시점 ~ 오디오 검사 완료까지 ms 측정
                    val timingStartMs = System.currentTimeMillis()
                    // Credit 세션 활성 시 항상 세션 패키지 우선(ALLOWED/BLOCKED 무관). 그 다음 BLOCKED 시 currentBlockedPackage, 없으면 null
                    val candidatePackage = when {
                        preferenceManager.isCreditSessionActive() -> preferenceManager.getCreditSessionPackage()
                        currentMiningState == MiningState.BLOCKED -> currentBlockedPackage
                        else -> null
                    }
                    val firstCheck = TimeCreditBackgroundService.computeAudioBlockedOnScreenOff(candidatePackage)
                    val elapsedMs = System.currentTimeMillis() - timingStartMs
                    Log.d(TAG, "[화면 OFF] 1차 오디오 검사 소요: ${elapsedMs}ms, audioBlocked=$firstCheck")
                    // 이중 검사: 200ms 후 재검사하여 OS 미디어 세션 정리 후에도 결과 반영 (audioBlocked = 1차 OR 2차)
                    mainHandler.postDelayed({
                        val secondCheck = TimeCreditBackgroundService.computeAudioBlockedOnScreenOff(candidatePackage)
                        val audioBlocked = firstCheck || secondCheck
                        if (secondCheck && !firstCheck) {
                            Log.d(TAG, "[화면 OFF] 200ms 재검사: audioBlocked=true로 보정")
                        }
                        preferenceManager.setAudioBlockedOnScreenOff(audioBlocked)
                        if (audioBlocked) {
                            Log.d(TAG, "[화면 OFF] 차단 앱 오디오 재생 중: 채굴 중지 상태 기록")
                        } else {
                            Log.d(TAG, "[화면 OFF] 차단 앱 오디오 재생 아님: 상태 기록 (false)")
                        }
                    }, 200L)

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
                        TimeCreditBackgroundService.resumeMining()
                    }
                    // Case 2: 오버레이 없이 차단 상태 -> 화면 OFF 시 홈 이동 제거 (화면 깜빡임 방지)
                    // 화면이 꺼진 상태에서는 사용자가 앱을 볼 수 없으므로 홈 이동 불필요
                    // 화면 ON 시 차단 앱이 보이면 자연스럽게 오버레이가 표시됨
                    else if (TimeCreditBackgroundService.isMiningPaused() && currentMiningState == MiningState.BLOCKED) {
                        Log.d(TAG, "차단 상태(오버레이 없음)에서 화면 OFF: 홈 이동 스킵 (화면 깜빡임 방지)")
                    } else if (TimeCreditBackgroundService.isMiningPaused() && currentMiningState == MiningState.ALLOWED) {
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

    // ===== 엄격모드: 설정 페이지 차단 로직 =====
    
    /**
     * 엄격모드 활성 상태를 확인합니다.
     * 
     * @return 엄격모드가 활성화되어 있으면 true
     */
    private fun isStrictModeActive(): Boolean {
        return try {
            StrictModeService.isStrictActive(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check strict mode active state", e)
            false
        }
    }

    /**
     * 설정 페이지를 검사하고 차단합니다.
     * rootInActiveWindow가 null인 경우 재시도 메커니즘을 포함합니다.
     */
    private fun checkAndBlockSettingsPage() {
        val currentTime = System.currentTimeMillis()
        
        // 쿨다운 체크 (너무 빈번한 검사 방지)
        if (currentTime - lastStrictModeCheckTime < STRICT_MODE_CHECK_COOLDOWN_MS) {
            return
        }
        lastStrictModeCheckTime = currentTime
        
        var rootNode = rootInActiveWindow
        
        // rootInActiveWindow가 null인 경우 재시도
        if (rootNode == null) {
            // 기존 재시도 코루틴 취소
            strictModeRetryJob?.cancel()
            strictModeRetryJob = serviceScope.launch {
                try {
                    delay(STRICT_MODE_RETRY_DELAY_MS)
                    rootNode = rootInActiveWindow
                    if (rootNode != null) {
                        try {
                            if (containsBlockedContent(rootNode)) {
                                Log.d(TAG, "엄격모드: 차단된 콘텐츠 감지 (재시도 후), 뒤로 가기 실행")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                showStrictModeToast()
                            }
                        } finally {
                            // rootNode 반환 (메모리 누수 방지)
                            rootNode?.recycle()
                        }
                    } else {
                        Log.w(TAG, "엄격모드: rootInActiveWindow가 null (UI 로딩 중일 수 있음)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "엄격모드 재시도 중 예외 발생", e)
                }
            }
            return
        }
        
        // rootNode가 null이 아닌 경우 즉시 검사
        try {
            if (containsBlockedContent(rootNode)) {
                Log.d(TAG, "엄격모드: 차단된 콘텐츠 감지, 뒤로 가기 실행")
                performGlobalAction(GLOBAL_ACTION_BACK)
                showStrictModeToast()
            }
        } finally {
            // rootNode 반환 (메모리 누수 방지)
            rootNode.recycle()
        }
    }

    /**
     * 노드에서 차단할 콘텐츠가 있는지 확인합니다.
     * 최적화된 단일 순회 알고리즘을 사용합니다:
     * - 트리를 한 번만 순회하면서 앱 이름, 차단 키워드, 토글 스위치를 동시에 검색
     * 
     * @param rootNode AccessibilityNodeInfo 루트 노드
     * @return 차단할 콘텐츠가 있으면 true
     */
    private fun containsBlockedContent(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) {
            return false
        }
        
        try {
            // 단일 순회로 모든 조건을 동시에 검색 (성능 최적화)
            val searchResult = searchBlockedContentInTree(rootNode)
            
            if (!searchResult.hasAppName) {
                // 앱 이름이 없으면 차단 불필요
                return false
            }
            
            // 앱 이름이 있고, 차단 키워드 또는 토글 스위치가 있으면 차단
            return searchResult.hasBlockedKeyword || searchResult.hasToggleSwitch
        } catch (e: Exception) {
            Log.e(TAG, "Error checking blocked content", e)
            return false
        }
    }
    
    /**
     * 검색 결과 데이터 클래스
     */
    private data class BlockedContentSearchResult(
        val hasAppName: Boolean = false,
        val hasBlockedKeyword: Boolean = false,
        val hasToggleSwitch: Boolean = false
    )
    
    /**
     * 전체 트리를 단일 순회로 검색하여 앱 이름, 차단 키워드, 토글 스위치를 동시에 찾습니다.
     * 성능 최적화: 3회 순회 → 1회 순회
     *
     * @param node AccessibilityNodeInfo 노드
     * @param depth 현재 탐색 깊이 (재귀용, 기본 0). MAX_SEARCH_DEPTH 도달 시 탐색 중단.
     * @return 검색 결과
     */
    private fun searchBlockedContentInTree(node: AccessibilityNodeInfo?, depth: Int = 0): BlockedContentSearchResult {
        if (node == null) {
            return BlockedContentSearchResult()
        }

        // [보완+정교화] Max Depth 안전장치: StackOverflow 사전 방어
        if (depth >= MAX_SEARCH_DEPTH) {
            Log.w(TAG, "searchBlockedContentInTree: 최대 탐색 깊이($MAX_SEARCH_DEPTH) 도달, 탐색 중단")
            return BlockedContentSearchResult()
        }
        
        var result = BlockedContentSearchResult()
        
        try {
            val text = node.text?.toString() ?: ""
            val contentDescription = node.contentDescription?.toString() ?: ""
            val allText = "$text $contentDescription"
            val className = node.className?.toString() ?: ""
            
            // 앱 이름 확인
            if (!result.hasAppName && (
                allText.contains(APP_NAME_KR, ignoreCase = true) ||
                allText.contains(APP_NAME_EN, ignoreCase = true)
            )) {
                result = result.copy(hasAppName = true)
            }
            
            // 차단 키워드 확인 (앱 이름이 있을 때만 의미 있음)
            if (!result.hasBlockedKeyword) {
                for (keyword in BLOCKED_KEYWORDS) {
                    if (allText.contains(keyword, ignoreCase = true)) {
                        result = result.copy(hasBlockedKeyword = true)
                        Log.d(TAG, "엄격모드: 차단 키워드 발견 - $keyword")
                        break
                    }
                }
            }
            
            // 토글 스위치 확인 (앱 이름이 있을 때만 의미 있음)
            if (!result.hasToggleSwitch && (
                className.contains("Switch", ignoreCase = true) ||
                className.contains("Toggle", ignoreCase = true) ||
                className.contains("SwitchCompat", ignoreCase = true)
            )) {
                result = result.copy(hasToggleSwitch = true)
                Log.d(TAG, "엄격모드: 토글 스위치 발견 - $className")
            }
            
            // 모든 조건을 만족하면 조기 종료 (성능 최적화)
            if (result.hasAppName && (result.hasBlockedKeyword || result.hasToggleSwitch)) {
                // 자식 노드는 검색하지 않고 반환
                return result
            }
            
            // 자식 노드 재귀 검색
            for (i in 0 until node.childCount) {
                val childNode = node.getChild(i) ?: continue
                try {
                    val childResult = searchBlockedContentInTree(childNode, depth + 1)
                    // 결과 병합
                    result = BlockedContentSearchResult(
                        hasAppName = result.hasAppName || childResult.hasAppName,
                        hasBlockedKeyword = result.hasBlockedKeyword || childResult.hasBlockedKeyword,
                        hasToggleSwitch = result.hasToggleSwitch || childResult.hasToggleSwitch
                    )

                    // 모든 조건을 만족하면 조기 종료
                    if (result.hasAppName && (result.hasBlockedKeyword || result.hasToggleSwitch)) {
                        return result
                    }
                } finally {
                    // [수정+정교화] 안전 recycle: Throwable 포착으로 Error(StackOverflowError 등)까지 방어
                    try {
                        childNode.recycle()
                    } catch (t: Throwable) {
                        Log.w(TAG, "childNode.recycle() 실패 (이미 해제된 노드이거나 Error 발생)", t)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching blocked content in tree", e)
        }
        
        return result
    }

    /**
     * 엄격모드 차단 시 토스트 메시지를 표시합니다.
     * 중복 표시 방지 메커니즘 포함.
     */
    private fun showStrictModeToast() {
        // 중복 표시 방지
        if (isStrictModeToastShowing) {
            return
        }
        
        isStrictModeToastShowing = true
        serviceScope.launch(Dispatchers.Main) {
            try {
                Toast.makeText(
                    this@AppBlockingService,
                    getString(R.string.strict_mode_blocked_message),
                    Toast.LENGTH_SHORT
                ).show()
                
                // 토스트 표시 시간 후 플래그 리셋 (약 2초)
                delay(2000)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing strict mode toast", e)
            } finally {
                isStrictModeToastShowing = false
            }
        }
    }
}