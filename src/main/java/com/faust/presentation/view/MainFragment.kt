package com.faust.presentation.view

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faust.R
import com.faust.data.utils.PreferenceManager
import com.faust.domain.StrictModeService
import com.faust.models.BlockedApp
import com.faust.presentation.viewmodel.MainViewModel
import com.faust.presentation.viewmodel.TimerUiState
import com.faust.services.AppBlockingService
import com.faust.services.FaustAdminReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 메인 Fragment (기존 MainActivity의 내용)
 * 차단 앱 목록 및 포인트 표시
 */
class MainFragment : Fragment() {
    private val viewModel: MainViewModel by viewModels()
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(requireContext())
    }
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: BlockedAppAdapter
    private lateinit var textCurrentPoints: TextView
    private lateinit var buttonAddApp: Button
    private lateinit var buttonPersona: Button
    private lateinit var textStrictModeStatus: TextView
    private lateinit var buttonEnableStrictMode: Button
    private lateinit var buttonEmergencyExit: Button

    private var durationMinutesPending: Int? = null
    private var strictModeTimer: CountDownTimer? = null

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (FaustAdminReceiver.isAdminActive(requireContext())) {
            // 기기 관리자 권한이 활성화되었으면 엄격모드 활성화
            durationMinutesPending?.let { minutes ->
                enableStrictMode(minutes)
                durationMinutesPending = null
            }
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.device_admin_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textCurrentPoints = view.findViewById(R.id.textCurrentPoints)
        buttonAddApp = view.findViewById(R.id.buttonAddApp)
        buttonPersona = view.findViewById(R.id.buttonPersona)
        textStrictModeStatus = view.findViewById(R.id.textStrictModeStatus)
        buttonEnableStrictMode = view.findViewById(R.id.buttonEnableStrictMode)
        buttonEmergencyExit = view.findViewById(R.id.buttonEmergencyExit)

        setupRecyclerView()
        setupViews()
        observeViewModel()
        updateStrictModeStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStrictModeStatus()
    }

    override fun onPause() {
        super.onPause()
        strictModeTimer?.cancel()
        strictModeTimer = null
    }

    private fun setupRecyclerView() {
        recyclerView = view?.findViewById(R.id.recyclerViewBlockedApps) ?: return
        adapter = BlockedAppAdapter { blockedApp ->
            removeBlockedApp(blockedApp)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupViews() {
        buttonAddApp.setOnClickListener {
            showAddAppDialog()
        }

        buttonPersona.setOnClickListener {
            showPersonaDialog()
        }

        buttonEnableStrictMode.setOnClickListener {
            showStrictModeDialog()
        }

        buttonEmergencyExit.setOnClickListener {
            // 쿨타임 체크
            val lastClickTime = preferenceManager.getEmergencyExitLastClickTime()
            val cooldownMinutes = preferenceManager.getEmergencyExitCooldownMinutes()
            val currentTime = System.currentTimeMillis()
            
            if (lastClickTime > 0 && cooldownMinutes > 0) {
                val cooldownEndTime = lastClickTime + (cooldownMinutes * 60 * 1000L)
                if (currentTime < cooldownEndTime) {
                    val remainingCooldownMinutes = TimeUnit.MILLISECONDS.toMinutes(cooldownEndTime - currentTime).toInt()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.strict_mode_emergency_exit_cooldown_blocked),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
            }
            
            showEmergencyExitDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.timerUiState.collect { state ->
                    updateTimerDisplay(state)
                    if (state is TimerUiState.Running) {
                        startTimerTick()
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.blockedApps.collect { apps ->
                if (::adapter.isInitialized) {
                    adapter.submitList(apps)
                }
            }
        }
    }

    private var timerTickJob: kotlinx.coroutines.Job? = null

    private fun startTimerTick() {
        timerTickJob?.cancel()
        timerTickJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val state = viewModel.timerUiState.value
                    when (state) {
                        is TimerUiState.Running -> {
                            val sec = viewModel.getRemainingSecondsForDisplay()
                            textCurrentPoints.text = getString(R.string.time_credit_timer, viewModel.formatTimeAsHhMmSs(sec))
                        }
                        is TimerUiState.Syncing -> {
                            textCurrentPoints.text = getString(R.string.time_credit_timer, "00:00:00")
                        }
                        else -> break
                    }
                    delay(1000L)
                }
            }
        }
    }

    private fun updateTimerDisplay(state: TimerUiState) {
        when (state) {
            is TimerUiState.Idle -> {
                timerTickJob?.cancel()
                timerTickJob = null
                textCurrentPoints.text = getString(R.string.time_credit_timer, viewModel.formatBalanceAsHhMmSs(state.balanceSeconds))
            }
            is TimerUiState.Running -> {
                textCurrentPoints.text = getString(R.string.time_credit_timer, viewModel.formatTimeAsHhMmSs(state.remainingSeconds))
            }
            is TimerUiState.Syncing -> {
                timerTickJob?.cancel()
                timerTickJob = null
                textCurrentPoints.text = getString(R.string.time_credit_timer, "00:00:00")
            }
        }
    }

    private fun showAddAppDialog() {
        lifecycleScope.launch {
            val maxApps = viewModel.getMaxBlockedApps()
            val currentApps = viewModel.blockedApps.value.size

            if (currentApps >= maxApps) {
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.max_apps_limit, maxApps),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val dialog = AppSelectionDialog { app ->
                lifecycleScope.launch {
                    val success = viewModel.addBlockedApp(app)
                    if (success) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            getString(R.string.app_added, app.appName),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            requireContext(),
                            getString(R.string.add_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            dialog.show(childFragmentManager, "AppSelectionDialog")
        }
    }

    private fun removeBlockedApp(blockedApp: com.faust.models.BlockedApp) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_app_title)
            .setMessage(getString(R.string.remove_app_message, blockedApp.appName))
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch {
                    viewModel.removeBlockedApp(blockedApp)
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.app_removed, blockedApp.appName),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPersonaDialog() {
        val dialog = PersonaSelectionDialog(preferenceManager) { personaTypeString ->
            if (personaTypeString != null) {
                preferenceManager.setPersonaType(personaTypeString)
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.persona_selected, personaTypeString),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                preferenceManager.setPersonaType("")
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.persona_unregister),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        dialog.show(childFragmentManager, "PersonaSelectionDialog")
    }

    private fun showStrictModeDialog() {
        // DisclosureDialogFragment 표시
        val disclosureDialog = DisclosureDialogFragment {
            // 동의 후 집중 시간 입력 다이얼로그 표시
            showDurationInputDialog()
        }
        disclosureDialog.show(childFragmentManager, "DisclosureDialogFragment")
    }

    private fun showDurationInputDialog() {
        // 커스텀 다이얼로그 레이아웃 생성
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_timer_picker, null)
        
        val numberPickerDay = dialogView.findViewById<NumberPicker>(R.id.numberPickerDay)
        val numberPickerHour = dialogView.findViewById<NumberPicker>(R.id.numberPickerHour)
        val numberPickerMinute = dialogView.findViewById<NumberPicker>(R.id.numberPickerMinute)
        
        // 일 NumberPicker 설정 (0-7, 최대 7일)
        numberPickerDay.minValue = 0
        numberPickerDay.maxValue = 7
        numberPickerDay.value = 0
        numberPickerDay.wrapSelectorWheel = false
        
        // 시간 NumberPicker 설정 (0-23)
        numberPickerHour.minValue = 0
        numberPickerHour.maxValue = 23
        numberPickerHour.value = 0
        numberPickerHour.wrapSelectorWheel = false
        
        // 분 NumberPicker 설정 (0-59)
        numberPickerMinute.minValue = 0
        numberPickerMinute.maxValue = 59
        numberPickerMinute.value = 30 // 기본값: 30분
        numberPickerMinute.wrapSelectorWheel = false
        
        // 다이얼로그 생성 및 표시
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.strict_mode_duration_title))
            .setMessage(getString(R.string.strict_mode_duration_message))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.strict_mode_enable)) { _, _ ->
                val days = numberPickerDay.value
                val hours = numberPickerHour.value
                val minutes = numberPickerMinute.value
                // 총 분 계산: 일 * 24 * 60 + 시간 * 60 + 분
                val totalMinutes = days * 24 * 60 + hours * 60 + minutes
                
                if (totalMinutes > 0) {
                    // 권한 확인 및 요청
                    checkAndRequestPermissions(totalMinutes)
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.strict_mode_invalid_duration),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun checkAndRequestPermissions(durationMinutes: Int) {
        // 접근성 서비스 권한 확인
        if (!AppBlockingService.isServiceEnabled(requireContext())) {
            // AppBlockingService 권한 요청 (앱 차단 및 엄격모드 보호 통합)
            AppBlockingService.requestAccessibilityPermission(requireContext())
            Toast.makeText(
                requireContext(),
                getString(R.string.strict_mode_accessibility_permission_required),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 기기 관리자 권한 확인
        if (!FaustAdminReceiver.isAdminActive(requireContext())) {
            requestDeviceAdminPermission(durationMinutes)
            return
        }

        // 모든 권한이 있으면 엄격모드 활성화
        enableStrictMode(durationMinutes)
    }

    private fun requestDeviceAdminPermission(durationMinutes: Int) {
        val devicePolicyManager = requireContext().getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = FaustAdminReceiver.getComponentName(requireContext())

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_explanation))

        // durationMinutes를 임시 저장 (권한 요청 후 사용)
        preferenceManager.setStrictModeActive(false) // 임시로 false 설정
        this.durationMinutesPending = durationMinutes

        deviceAdminLauncher.launch(intent)
    }

    private fun enableStrictMode(durationMinutes: Int) {
        StrictModeService.enableStrictMode(requireContext(), durationMinutes)
        updateStrictModeStatus()
        Toast.makeText(
            requireContext(),
            getString(R.string.strict_mode_enabled, durationMinutes),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 남은 시간을 일/시/분 형식으로 포맷팅합니다.
     * @param millis 남은 시간 (밀리초)
     * @return "x일 x시간 x분" 형식의 문자열
     */
    private fun formatTimeRemaining(millis: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        
        return getString(R.string.strict_mode_active_dhm, days.toInt(), hours.toInt(), minutes.toInt())
    }

    private fun updateStrictModeStatus() {
        val isActive = StrictModeService.isStrictActive(requireContext())
        
        // 기존 타이머 취소
        strictModeTimer?.cancel()
        strictModeTimer = null
        
        if (isActive) {
            val remainingTime = StrictModeService.getRemainingTime(requireContext())
            val remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingTime)
            val remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(remainingTime)
            
            // 즉시 UI 업데이트
            if (remainingMinutes >= 1) {
                textStrictModeStatus.text = formatTimeRemaining(remainingTime)
            } else {
                textStrictModeStatus.text = getString(R.string.strict_mode_active_seconds, remainingSeconds.toInt())
            }
            buttonEnableStrictMode.text = getString(R.string.strict_mode_disable)
            buttonEnableStrictMode.setOnClickListener {
                StrictModeService.disableStrictProtection(requireContext())
                updateStrictModeStatus()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.strict_mode_disabled),
                    Toast.LENGTH_SHORT
                ).show()
            }
            buttonEmergencyExit.visibility = View.VISIBLE
            
            // 쿨타임 체크 및 버튼 상태 업데이트
            val lastClickTime = preferenceManager.getEmergencyExitLastClickTime()
            val cooldownMinutes = preferenceManager.getEmergencyExitCooldownMinutes()
            val currentTime = System.currentTimeMillis()
            
            if (lastClickTime > 0 && cooldownMinutes > 0) {
                val cooldownEndTime = lastClickTime + (cooldownMinutes * 60 * 1000L)
                if (currentTime < cooldownEndTime) {
                    val remainingCooldownMinutes = TimeUnit.MILLISECONDS.toMinutes(cooldownEndTime - currentTime).toInt()
                    buttonEmergencyExit.isEnabled = false
                    buttonEmergencyExit.text = getString(R.string.strict_mode_emergency_exit_cooldown_active, remainingCooldownMinutes)
                } else {
                    buttonEmergencyExit.isEnabled = true
                    buttonEmergencyExit.text = getString(R.string.strict_mode_emergency_exit)
                }
            } else {
                buttonEmergencyExit.isEnabled = true
                buttonEmergencyExit.text = getString(R.string.strict_mode_emergency_exit)
            }
            
            // 타이머 시작: 1분 이상일 때는 1분 간격, 1분 미만일 때는 1초 간격
            val tickInterval = if (remainingTime >= 60_000L) 60_000L else 1_000L
            strictModeTimer = object : CountDownTimer(remainingTime, tickInterval) {
                override fun onTick(millisUntilFinished: Long) {
                    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)
                    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished)
                    
                    // 1분 이상일 때는 일/시/분 형식으로 표시, 1분 미만일 때는 초 단위로 표시
                    if (totalMinutes >= 1) {
                        textStrictModeStatus.text = formatTimeRemaining(millisUntilFinished)
                    } else {
                        // 1분 미만: 초 단위로 표시 (예: "30초 남음")
                        textStrictModeStatus.text = getString(R.string.strict_mode_active_seconds, totalSeconds.toInt())
                    }
                    
                    // 쿨타임 체크 및 버튼 상태 업데이트 (1초 주기로 체크)
                    val lastClickTime = preferenceManager.getEmergencyExitLastClickTime()
                    val cooldownMinutes = preferenceManager.getEmergencyExitCooldownMinutes()
                    val currentTime = System.currentTimeMillis()
                    
                    if (lastClickTime > 0 && cooldownMinutes > 0) {
                        val cooldownEndTime = lastClickTime + (cooldownMinutes * 60 * 1000L)
                        if (currentTime < cooldownEndTime) {
                            val remainingCooldownMinutes = TimeUnit.MILLISECONDS.toMinutes(cooldownEndTime - currentTime).toInt()
                            buttonEmergencyExit.isEnabled = false
                            buttonEmergencyExit.text = getString(R.string.strict_mode_emergency_exit_cooldown_active, remainingCooldownMinutes)
                        } else {
                            buttonEmergencyExit.isEnabled = true
                            buttonEmergencyExit.text = getString(R.string.strict_mode_emergency_exit)
                        }
                    } else {
                        buttonEmergencyExit.isEnabled = true
                        buttonEmergencyExit.text = getString(R.string.strict_mode_emergency_exit)
                    }
                }
                
                override fun onFinish() {
                    // 시간 만료 시: 타이머 정리 및 엄격모드 해제, 초기 상태로 복귀
                    strictModeTimer?.cancel()
                    strictModeTimer = null
                    
                    // 엄격모드 해제 (StrictModeService.isStrictActive()에서 자동 해제되지만 명시적으로 확인)
                    // getRemainingTime()이 0을 반환하면 이미 해제된 상태
                    val remainingTime = StrictModeService.getRemainingTime(requireContext())
                    if (remainingTime <= 0) {
                        // 이미 해제되었거나 해제 중인 경우, UI만 초기 상태로 복귀
                        textStrictModeStatus.text = getString(R.string.strict_mode_inactive)
                        buttonEnableStrictMode.text = getString(R.string.strict_mode_enable)
                        buttonEnableStrictMode.setOnClickListener {
                            showStrictModeDialog()
                        }
                        buttonEmergencyExit.visibility = View.GONE
                        
                        // 만료 알림
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.strict_mode_expired),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // 예외 상황: 아직 시간이 남아있는 경우 (시스템 시간 변경 등)
                        // 상태 재확인
                        updateStrictModeStatus()
                    }
                }
            }.start()
        } else {
            textStrictModeStatus.text = getString(R.string.strict_mode_inactive)
            buttonEnableStrictMode.text = getString(R.string.strict_mode_enable)
            buttonEnableStrictMode.setOnClickListener {
                showStrictModeDialog()
            }
            buttonEmergencyExit.visibility = View.GONE
        }
    }

    private fun showEmergencyExitDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.strict_mode_emergency_exit_title))
            .setMessage(getString(R.string.strict_mode_emergency_exit_message, preferenceManager.getEmergencyExitDelayMinutes()))
            .setPositiveButton(getString(R.string.strict_mode_emergency_exit_confirm)) { _, _ ->
                // 이중 체크: 쿨타임 재확인
                val lastClickTime = preferenceManager.getEmergencyExitLastClickTime()
                val cooldownMinutes = preferenceManager.getEmergencyExitCooldownMinutes()
                val currentTime = System.currentTimeMillis()
                
                if (lastClickTime > 0 && cooldownMinutes > 0) {
                    val cooldownEndTime = lastClickTime + (cooldownMinutes * 60 * 1000L)
                    if (currentTime < cooldownEndTime) {
                        val remainingCooldownMinutes = TimeUnit.MILLISECONDS.toMinutes(cooldownEndTime - currentTime).toInt()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.strict_mode_emergency_exit_cooldown_blocked),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }
                }
                
                // 비상구 처리
                val result = StrictModeService.processEmergencyExit(requireContext())
                
                if (result.success) {
                    // 성공 시 UI 업데이트
                    updateStrictModeStatus()
                    
                    // 메시지 표시
                    Toast.makeText(
                        requireContext(),
                        result.message,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // 실패 시 메시지만 표시
                    Toast.makeText(
                        requireContext(),
                        result.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }
}
