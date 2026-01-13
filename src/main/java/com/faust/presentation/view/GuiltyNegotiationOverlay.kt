package com.faust.presentation.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.faust.R
import com.faust.data.utils.PreferenceManager
import com.faust.domain.PenaltyService
import com.faust.domain.persona.PersonaEngine
import com.faust.domain.persona.PersonaProvider
import com.faust.domain.persona.handlers.AudioHandler
import com.faust.domain.persona.handlers.AudioHandlerImpl
import com.faust.domain.persona.handlers.HapticHandler
import com.faust.domain.persona.handlers.HapticHandlerImpl
import com.faust.domain.persona.handlers.VisualHandler
import com.faust.domain.persona.handlers.VisualHandlerImpl
import com.faust.services.AppBlockingService
import kotlinx.coroutines.*

/**
 * [핵심 이벤트: 차단 관련 이벤트 - showOverlay]
 * 
 * 역할: 차단 대상 앱임이 확인되면 4~6초의 지연 후 화면 최상단에 표시되는 오버레이입니다.
 * 트리거: AppBlockingService.showOverlay() 호출
 * 처리: WindowManager를 통해 시스템 레벨 오버레이 표시, 30초 카운트다운 시작, 강행/철회 버튼 제공
 * 
 * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
 */
class GuiltyNegotiationOverlay(
    private val context: Context
) : LifecycleOwner {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private var countdownJob: Job? = null
    private val penaltyService = PenaltyService(context)
    private var packageName: String = ""
    private var appName: String = ""
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isUserActionCompleted: Boolean = false // 사용자 액션 완료 여부
    
    // Persona Module
    private val personaEngine: PersonaEngine by lazy {
        val preferenceManager = PreferenceManager(context)
        val personaProvider = PersonaProvider(preferenceManager, context)
        val visualHandler: VisualHandler = VisualHandlerImpl()
        val hapticHandler: HapticHandler = HapticHandlerImpl(context)
        val audioHandler: AudioHandler = AudioHandlerImpl(context)
        
        PersonaEngine(
            personaProvider = personaProvider,
            visualHandler = visualHandler,
            hapticHandler = hapticHandler,
            audioHandler = audioHandler,
            context = context
        )
    }
    
    private var headsetReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "GuiltyNegotiationOverlay"
    }

    init {
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    /**
     * [핵심 이벤트: 차단 관련 이벤트 - showOverlay]
     * 
     * 역할: 오버레이를 화면에 표시합니다.
     * 트리거: AppBlockingService.showOverlay() 호출
     * 처리: WindowManager를 통해 시스템 레벨 오버레이 추가, 30초 카운트다운 시작
     */
    fun show(packageName: String, appName: String) {
        this.packageName = packageName
        this.appName = appName
        isUserActionCompleted = false // 사용자 액션 플래그 초기화

        if (overlayView != null) {
            Log.d(TAG, "Overlay already showing, skipping")
            return // 이미 표시 중
        }

        // 오버레이 권한 확인 (BadTokenException 방지)
        val hasPermission = checkOverlayPermission()
        Log.d(TAG, "Overlay permission check: $hasPermission")
        
        if (!hasPermission) {
            Log.w(TAG, "Overlay permission not granted, cannot show overlay")
            Log.w(TAG, "Settings.canDrawOverlays(context) = ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else "N/A (API < 23)"}")
            return
        }

        // 권한이 있는 경우 즉시 오버레이 표시 시도
        Log.d(TAG, "Overlay permission granted, attempting to show overlay")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: run {
                Log.e(TAG, "WindowManager service not available")
                return
            }
        this.windowManager = windowManager

        overlayView = createOverlayView()
        overlayView?.let { view ->
            val params = createWindowParams()
            try {
                windowManager.addView(view, params)
                Log.d(TAG, "Overlay view added successfully")
                
                // Persona 피드백 실행
                val textPrompt = view.findViewById<TextView>(R.id.textPrompt)
                val editInput = view.findViewById<EditText>(R.id.editInput)
                val proceedButton = view.findViewById<Button>(R.id.buttonProceed)
                
                // 지연 시간을 300ms로 늘려 안정성 확보
                view.postDelayed({
                    editInput.requestFocus()
                    
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    // [변경] showSoftInput 대신 toggleSoftInput 사용 (강제성 높음)
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
                    
                    Log.d(TAG, "Keyboard toggled via SHOW_FORCED")
                }, 300)
                
                coroutineScope.launch {
                    val profile = personaEngine.getPersonaProfile()
                    personaEngine.executeFeedback(profile, textPrompt, editInput, proceedButton)
                }
                
                // 헤드셋 리스너 등록
                registerHeadsetReceiver()
                
                startCountdown()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay view", e)
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
                // 오버레이 추가 실패
                overlayView = null
            }
        } ?: run {
            Log.e(TAG, "Failed to create overlay view")
        }
    }

    /**
     * 오버레이 권한이 있는지 확인합니다.
     * BadTokenException 방지를 위해 필수입니다.
     */
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPermission = Settings.canDrawOverlays(context)
            Log.d(TAG, "checkOverlayPermission: SDK >= M, result = $hasPermission")
            hasPermission
        } else {
            Log.d(TAG, "checkOverlayPermission: SDK < M, returning true")
            true
        }
    }

    /**
     * 오버레이를 닫습니다.
     * 사용자가 버튼을 누르기 전까지는 호출되지 않도록 보호됩니다.
     * @param force 강제로 닫을지 여부 (기본값: false, 사용자 액션으로만 닫을 수 있음)
     */
    fun dismiss(force: Boolean = false) {
        // 사용자 액션이 완료되지 않았고 강제가 아니면 닫지 않음
        if (!isUserActionCompleted && !force) {
            Log.w(TAG, "dismiss() called but user action not completed. Ignoring dismiss request.")
            Log.w(TAG, "Overlay can only be dismissed after user clicks proceed or cancel button.")
            return
        }
        
        Log.d(TAG, "Dismissing overlay (force=$force, userActionCompleted=$isUserActionCompleted)")
        
        // Persona 피드백 정지 (가장 중요!)
        personaEngine.stopAll()
        
        // 헤드셋 리스너 해제
        unregisterHeadsetReceiver()
        
        countdownJob?.cancel()
        coroutineScope.cancel()
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "Overlay view removed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            }
        }
        overlayView = null
        windowManager = null
        isUserActionCompleted = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun createOverlayView(): View {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_guilty_negotiation, null)

        val titleText = view.findViewById<TextView>(R.id.textTitle)
        val messageText = view.findViewById<TextView>(R.id.textMessage)
        val countdownText = view.findViewById<TextView>(R.id.textCountdown)
        val proceedButton = view.findViewById<Button>(R.id.buttonProceed)
        val cancelButton = view.findViewById<Button>(R.id.buttonCancel)

        titleText.text = context.getString(R.string.guilty_negotiation_title)
        messageText.text = context.getString(R.string.guilty_negotiation_message)

        proceedButton.setOnClickListener {
            onProceed()
        }

        cancelButton.setOnClickListener {
            onCancel()
        }

        // 30초 카운트다운 시작
        startCountdown(countdownText)

        return view
    }

    private fun startCountdown(countdownText: TextView? = null) {
        countdownJob?.cancel()
        countdownJob = coroutineScope.launch {
            var remainingSeconds = 30
            val textView = countdownText ?: overlayView?.findViewById(R.id.textCountdown)

            while (remainingSeconds > 0 && isActive) {
                textView?.text = context.getString(R.string.wait_time, remainingSeconds)
                delay(1000)
                remainingSeconds--
            }

            if (isActive && remainingSeconds == 0) {
                // 30초 경과 후에도 버튼 활성화
                textView?.text = context.getString(R.string.wait_time, 0)
            }
        }
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - onProceed]
     * 
     * 역할: 사용자가 오버레이에서 '강행'을 선택할 때 발생하며, PenaltyService를 통해 3 WP를 차감하고 오버레이를 닫습니다.
     * 트리거: 사용자가 오버레이의 '강행' 버튼 클릭
     * 처리: PenaltyService.applyLaunchPenalty() 호출 (Free 티어: 3 WP 차감), 오버레이 닫기
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun onProceed() {
        Log.d(TAG, "User clicked proceed button")
        
        // 가장 중요: 즉시 모든 피드백 정지
        personaEngine.stopAll()
        
        isUserActionCompleted = true
        
        // 현재 context(AppBlockingService)에 허용 패키지 등록 (Grace Period)
        (context as? AppBlockingService)?.setAllowedPackage(packageName)
        
        // 강행 실행 - 페널티 적용
        penaltyService.applyLaunchPenalty(packageName, appName)
        dismiss(force = true)
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - onCancel]
     * 
     * 역할: 사용자가 '철회'를 선택할 때 발생하며, 오버레이를 닫고 해당 앱 사용을 중단하도록 유도합니다 (Free 티어는 페널티 0).
     * 트리거: 사용자가 오버레이의 '철회' 버튼 클릭
     * 처리: PenaltyService.applyQuitPenalty() 호출 (Free 티어: 페널티 0), 홈 화면으로 이동, 오버레이 닫기
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun onCancel() {
        Log.d(TAG, "User clicked cancel button")
        
        // 가장 중요: 즉시 모든 피드백 정지
        personaEngine.stopAll()
        
        isUserActionCompleted = true
        // 철회 - Free 티어는 페널티 없음
        penaltyService.applyQuitPenalty(packageName, appName)
        
        // 홈 화면으로 이동
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Log.d(TAG, "Launched home screen intent")
        
        // 오버레이 제거
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "Overlay view removed after cancel")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view after cancel", e)
            }
        }
        
        // 리소스 정리
        countdownJob?.cancel()
        coroutineScope.cancel()
        overlayView = null
        windowManager = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun createWindowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // [핵심] 플래그를 최소화하여 충돌 방지
            // FLAG_NOT_FOCUSABLE 절대 금지
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or // 바깥 터치 감지 (선택 사항)
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or     // 잠금 화면 위 표시
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,  // 하드웨어 가속 활성화
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.5f
            // ALWAYS_VISIBLE로 윈도우 생성 시점부터 키보드 공간 확보 시도
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }
    
    /**
     * 헤드셋 연결/해제를 감지하는 BroadcastReceiver를 등록합니다.
     * 오버레이가 표시되는 동안에만 활성화됩니다 (한시적 리스너).
     */
    private fun registerHeadsetReceiver() {
        try {
            headsetReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                        Log.d(TAG, "Headset disconnected, switching feedback mode")
                        // 이어폰 탈착 시 피드백 모드 전환
                        coroutineScope.launch {
                            personaEngine.stopAll()
                            overlayView?.let { view ->
                                val textPrompt = view.findViewById<TextView>(R.id.textPrompt)
                                val editInput = view.findViewById<EditText>(R.id.editInput)
                                val proceedButton = view.findViewById<Button>(R.id.buttonProceed)
                                val profile = personaEngine.getPersonaProfile()
                                personaEngine.executeFeedback(profile, textPrompt, editInput, proceedButton)
                            }
                        }
                    }
                }
            }
            
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            context.registerReceiver(headsetReceiver, filter)
            Log.d(TAG, "Headset receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register headset receiver", e)
        }
    }
    
    /**
     * 헤드셋 리스너를 해제합니다.
     */
    private fun unregisterHeadsetReceiver() {
        try {
            headsetReceiver?.let {
                context.unregisterReceiver(it)
                headsetReceiver = null
                Log.d(TAG, "Headset receiver unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister headset receiver", e)
        }
    }
}
