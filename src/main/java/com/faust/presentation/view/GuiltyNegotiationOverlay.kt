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
 * 오버레이 닫힘 완료 콜백 인터페이스
 */
interface OverlayDismissCallback {
    fun onDismissed()
}

/**
 * [핵심 이벤트: 차단 관련 이벤트 - showOverlay]
 * * 역할: 차단 대상 앱임이 확인되면 화면 최상단에 표시되는 오버레이입니다.
 */
class GuiltyNegotiationOverlay(
    private val context: Context,
    private val dismissCallback: OverlayDismissCallback? = null
) : LifecycleOwner {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private var countdownJob: Job? = null
    private val penaltyService = PenaltyService(context)
    private var packageName: String = ""
    private var appName: String = ""
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isUserActionCompleted: Boolean = false

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

    fun show(packageName: String, appName: String) {
        this.packageName = packageName
        this.appName = appName
        isUserActionCompleted = false

        if (overlayView != null) return

        if (!checkOverlayPermission()) return

        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        this.windowManager = windowManager

        overlayView = createOverlayView()
        overlayView?.let { view ->
            val params = createWindowParams()
            try {
                windowManager.addView(view, params)

                // Persona 피드백
                val textPrompt = view.findViewById<TextView>(R.id.textPrompt)
                val editInput = view.findViewById<EditText>(R.id.editInput)
                val proceedButton = view.findViewById<Button>(R.id.buttonProceed)

                view.post {
                    editInput.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(editInput, InputMethodManager.SHOW_IMPLICIT)
                }

                coroutineScope.launch {
                    val profile = personaEngine.getPersonaProfile()
                    personaEngine.executeFeedback(profile, textPrompt, editInput, proceedButton)
                }

                registerHeadsetReceiver()
                startCountdown()
            } catch (e: Exception) {
                overlayView = null
            }
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun dismiss(force: Boolean = false) {
        if (!isUserActionCompleted && !force) return

        Log.d(TAG, "Dismissing overlay (force=$force)")

        personaEngine.stopAll()
        unregisterHeadsetReceiver()
        countdownJob?.cancel()
        coroutineScope.cancel()

        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                // 뷰 제거 완료 후 콜백 호출 (메인 스레드에서 실행)
                view.post {
                    dismissCallback?.onDismissed()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
                // 실패해도 콜백 호출 (상태 복구를 위해)
                dismissCallback?.onDismissed()
            }
        } ?: run {
            // overlayView가 null이어도 콜백 호출
            dismissCallback?.onDismissed()
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

        proceedButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    onProceed()
                    true
                }
                else -> false
            }
        }

        cancelButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    onCancel()
                    true
                }
                else -> false
            }
        }

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
                textView?.text = context.getString(R.string.wait_time, 0)
            }
        }
    }

    /**
     * [핵심 수정] 강행 처리
     * PenaltyService 경유로 패널티 적용 후 오버레이 닫기.
     */
    private fun onProceed() {
        if (isUserActionCompleted) {
            Log.d(TAG, "onProceed: 이미 처리 완료, 중복 호출 무시")
            return
        }
        Log.d(TAG, "User clicked proceed button")
        personaEngine.stopAll()
        isUserActionCompleted = true
        (context as? AppBlockingService)?.setAllowedPackage(packageName)

        coroutineScope.launch {
            penaltyService.applyLaunchPenalty(packageName, appName)
            (context as? AppBlockingService)?.hideOverlay(shouldGoHome = false)
        }
    }

    /**
     * [핵심 수정] 철회 처리
     * 서비스를 통해 확실하게 홈 화면으로 이동합니다.
     */
    private fun onCancel() {
        if (isUserActionCompleted) {
            Log.d(TAG, "onCancel: 이미 처리 완료, 중복 호출 무시")
            return
        }
        Log.d(TAG, "User clicked cancel button")
        personaEngine.stopAll()
        isUserActionCompleted = true

        coroutineScope.launch {
            // 페널티 적용 및 결과 확인
            val penaltyApplied = penaltyService.applyQuitPenalty(packageName, appName)
            
            // 패널티 적용 실패 시 로깅 (Grace Period는 적용하지 않음)
            if (!penaltyApplied) {
                Log.w(TAG, "패널티 적용 실패: 포인트 부족 또는 오류 발생 ($packageName)")
            }

            // [핵심] 서비스에게 오버레이 닫기 및 홈 이동 요청
            // shouldGoHome = true -> 강제로 홈으로 튕겨냄
            // applyCooldown = false -> 철회 버튼 클릭 시 쿨다운 면제 (빠른 재실행 허용)
            (context as? AppBlockingService)?.hideOverlay(shouldGoHome = true, applyCooldown = false)
        }
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
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.5f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun registerHeadsetReceiver() {
        try {
            headsetReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register headset receiver", e)
        }
    }

    private fun unregisterHeadsetReceiver() {
        try {
            headsetReceiver?.let {
                context.unregisterReceiver(it)
                headsetReceiver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister headset receiver", e)
        }
    }
}