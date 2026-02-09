package com.faust.presentation.view

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.faust.R
import com.faust.data.utils.PreferenceManager
import com.faust.domain.AppGroupService
import com.faust.domain.DailyResetService
import com.faust.domain.TimeCreditService
import com.faust.domain.WeeklyResetService
import com.faust.models.BlockedApp
import com.faust.utils.AppLog
import com.faust.presentation.view.AppSelectionDialog
import com.faust.presentation.view.BlockedAppAdapter
import com.faust.presentation.view.PersonaSelectionDialog
import com.faust.presentation.viewmodel.MainViewModel
import com.faust.services.AppBlockingService
import com.faust.services.TimeCreditBackgroundService
import androidx.work.WorkManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [시스템 진입점: 사용자 진입점]
 * 
 * 역할: 사용자가 앱 아이콘을 눌러 실행하는 지점으로, 차단 앱 설정 및 포인트 현황을 확인하는 UI 레이어의 시작점입니다.
 * 트리거: 사용자가 홈 화면 또는 앱 목록에서 Faust 앱 아이콘 클릭
 * 처리: UI 초기화, 권한 확인, 차단 앱 목록 표시, 포인트 현황 실시간 표시
 * 
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var fabStartService: FloatingActionButton
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("faust_alarm_prefs", Context.MODE_PRIVATE)
    }
    
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(this)
    }
    
    private companion object {
        private const val TAG = "MainActivity"
        private const val KEY_ALARM_PERMISSION_ERROR = "alarm_permission_error_occurred"
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Settings.canDrawOverlays(this)) {
            startServices()
        } else {
            Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    private val accessibilityPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (checkAccessibilityService()) {
            startServices()
        } else {
            Toast.makeText(this, getString(R.string.accessibility_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isIgnoringBatteryOptimizations()) {
            startServices()
        } else {
            Toast.makeText(this, getString(R.string.battery_optimization_required), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * [시스템 진입점: 사용자 진입점]
     * 
     * 역할: 앱 실행 시 초기화 및 UI 설정
     * 트리거: 사용자가 앱 아이콘 클릭하여 Activity 시작
     * 처리: 레이아웃 설정, 권한 확인, 서비스 시작, 포인트 관찰 시작
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupToolbar()
        setupViewPager()
        setupViews()
        
        // 앱 시작 시 권한 확인
        checkPermissionsOnStart()
        
        checkPermissions()
        
        // 주간 정산 스케줄링
        if (!hasAlarmPermissionError()) {
            try {
                WeeklyResetService.scheduleWeeklyReset(this)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException when scheduling weekly reset in onCreate", e)
                // 권한 에러 발생 시 플래그 저장
                saveAlarmPermissionError()
                // 비정확 알람으로 재시도하지 않음 (이미 WeeklyResetService 내부에서 처리됨)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception when scheduling weekly reset", e)
            }
        } else {
            Log.w(TAG, "Skipping weekly reset scheduling: alarm permission error occurred previously")
        }

        // 일일 초기화 스케줄링
        try {
            DailyResetService.scheduleDailyReset(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule daily reset", e)
        }

        // 앱 그룹 초기화 (앱 첫 실행 시)
        lifecycleScope.launch {
            try {
                val appGroupService = AppGroupService(this@MainActivity)
                appGroupService.initializeDefaultGroups()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize app groups", e)
            }
        }

        // Time Credit 실시간 정산: 앱이 포그라운드(override on top)일 때 60초마다 정산하여 잔액 갱신
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runSettlementInForeground()
                while (true) {
                    delay(60_000L)
                    runSettlementInForeground()
                }
            }
        }

        // [M-2] 기존 FreePass WorkManager 작업 정리 (PassExpirationWorker 취소)
        try {
            WorkManager.getInstance(this).cancelAllWorkByTag("pass_expiration")
            Log.d(TAG, "PassExpirationWorker 정리 완료")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel pass_expiration workers", e)
        }
    }

    override fun onDestroy() {
        try {
            preferenceManager.persistTimeCreditValues(synchronous = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist time credit on exit", e)
        }
        super.onDestroy()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            // 액션바가 이미 설정되어 있지 않은 경우에만 설정 (중복 방지)
            if (supportActionBar == null) {
                setSupportActionBar(toolbar)
            }
            supportActionBar?.title = getString(R.string.app_name)
        } else {
            android.util.Log.e("MainActivity", "Toolbar not found with ID: toolbar")
        }
    }

    private fun setupViewPager() {
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val adapter = MainPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.app_name) // "Faust" 또는 "홈"
                1 -> "타임크레딧"
                2 -> getString(R.string.settings_title)
                else -> null
            }
        }.attach()
    }

    private fun setupViews() {
        fabStartService = findViewById(R.id.fabStartService)

        fabStartService.setOnClickListener {
            if (checkAllPermissions()) {
                startServices()
            } else {
                checkPermissions()
            }
        }
    }

    private fun checkPermissions() {
        if (!checkAllPermissions()) {
            showPermissionDialog()
        }
    }

    private fun checkAllPermissions(): Boolean {
        return checkAccessibilityService() && checkOverlayPermission()
    }

    /**
     * 배터리 최적화 제외 여부를 확인합니다.
     * @return 배터리 최적화에서 제외되어 있으면 true, 아니면 false
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Android M 이하는 항상 true
        }
    }

    /**
     * 배터리 최적화 제외가 필요한지 확인하고, 필요하면 요청합니다.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations()) {
                showBatteryOptimizationDialog()
            } else {
                startServices()
            }
        } else {
            startServices()
        }
    }

    /**
     * 배터리 최적화 제외 요청 다이얼로그를 표시합니다.
     */
    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_optimization_title))
            .setMessage(getString(R.string.battery_optimization_message))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                requestBatteryOptimizationExclusion()
            }
            .setNegativeButton(getString(R.string.later)) { _, _ ->
                // 사용자가 나중에 하기로 선택해도 서비스는 시작
                startServices()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 배터리 최적화 제외 설정 화면으로 이동합니다.
     */
    private fun requestBatteryOptimizationExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryOptimizationLauncher.launch(intent)
        }
    }

    /**
     * 시스템 알림이 꺼져 있으면 다이얼로그로 안내하고, [설정으로 이동] 시 앱 알림 설정 화면을 엽니다.
     * Grace Period 등 앱 차단 알림을 받으려면 알림이 켜져 있어야 합니다.
     */
    private fun checkNotificationPermissionAndPrompt() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val dialogShownKey = "notification_disabled_dialog_shown"
            val prefs = getSharedPreferences("faust_notification_prompt", Context.MODE_PRIVATE)
            if (!prefs.getBoolean(dialogShownKey, false)) {
                prefs.edit().putBoolean(dialogShownKey, true).apply()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.notification_disabled_title))
                    .setMessage(getString(R.string.notification_disabled_message))
                    .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            }
                            startActivity(intent)
                        } else {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            })
                        }
                    }
                    .setNegativeButton(getString(R.string.later), null)
                    .show()
            }
        }
    }

    /**
     * 알림 액세스(NotificationListenerService) 허용 여부를 확인합니다.
     * MediaSessionManager.getActiveSessions()로 정밀 오디오 감지를 위해 필요합니다.
     */
    private fun isNotificationListenerEnabled(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    /**
     * 알림 액세스가 꺼져 있으면 한 번만 다이얼로그로 안내하고, [설정으로 이동] 시 알림 액세스 설정 화면을 엽니다.
     */
    private fun checkNotificationAccessAndPrompt() {
        if (isNotificationListenerEnabled()) return
        val prefs = getSharedPreferences("faust_notification_access_prompt", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notification_access_dialog_shown", false)) return
        prefs.edit().putBoolean("notification_access_dialog_shown", true).apply()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notification_access_required_title))
            .setMessage(getString(R.string.notification_access_required_message, getString(R.string.app_name)))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } else {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
            }
            .setNegativeButton(getString(R.string.later), null)
            .show()
    }

    private fun checkAccessibilityService(): Boolean {
        return AppBlockingService.isServiceEnabled(this)
    }

    /**
     * '다른 앱 위에 표시' 권한이 있는지 확인합니다.
     * @return 권한이 있으면 true, 없으면 false
     */
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * 앱 시작 시 필수 권한을 확인하고, 없으면 설정 화면으로 유도합니다.
     */
    private fun checkPermissionsOnStart() {
        val missingPermissions = mutableListOf<String>()
        
        if (!checkOverlayPermission()) {
            missingPermissions.add(getString(R.string.overlay_permission_name))
        }
        
        if (missingPermissions.isNotEmpty()) {
            val permissionsList = missingPermissions.joinToString("\n") { "• $it" }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permissions_required_title))
                .setMessage(getString(R.string.permissions_required_message, permissionsList))
                .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                    requestPermissions()
                }
                .setNegativeButton(getString(R.string.later), null)
                .setCancelable(false)
                .show()
        }
    }
    
    /**
     * 오버레이 권한 설정 화면으로 이동합니다.
     */
    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permissions_required_title))
            .setMessage(getString(R.string.permission_dialog_message))
            .setPositiveButton(getString(R.string.settings)) { _, _ ->
                requestPermissions()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun requestPermissions() {
        if (!checkAccessibilityService()) {
            AppBlockingService.requestAccessibilityPermission(this)
            // 접근성 설정 화면에서 돌아올 때 체크하기 위해 onResume에서 확인
        } else if (!checkOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            // 모든 권한이 있고 배터리 최적화 제외만 필요한 경우
            checkBatteryOptimization()
        }
    }

    private fun startServices() {
        if (checkAllPermissions()) {
            // 접근성 서비스는 시스템이 자동으로 시작하므로 별도 시작 불필요
            TimeCreditBackgroundService.startService(this)
            Toast.makeText(this, getString(R.string.service_started), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.all_permissions_required), Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.i(TAG, "${AppLog.LIFECYCLE} user returned to app → foreground, running permission/settlement checks")

        // 권한 재확인 및 버튼 활성화 상태 업데이트
        updateServiceButtonState()

        // 알림 권한 확인: 비활성화 시 Grace Period 등 앱 차단 알림을 받을 수 없음을 안내
        checkNotificationPermissionAndPrompt()

        // 알림 액세스(Notification Listener) 확인: 미허용 시 정밀 오디오 감지 안내
        checkNotificationAccessAndPrompt()

        // 모든 권한이 활성화되었는지 확인
        if (checkAccessibilityService() && checkOverlayPermission()) {
            // 배터리 최적화 제외도 확인 (선택사항이지만 권장)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
                // 배터리 최적화 제외가 안 되어 있으면 한 번만 안내
                val prefs = getSharedPreferences("faust_battery_opt", Context.MODE_PRIVATE)
                val hasShownDialog = prefs.getBoolean("has_shown_battery_opt_dialog", false)
                if (!hasShownDialog) {
                    prefs.edit().putBoolean("has_shown_battery_opt_dialog", true).apply()
                    checkBatteryOptimization()
                } else {
                    // 이미 안내했으면 서비스는 시작
                    TimeCreditBackgroundService.startService(this)
                }
            } else {
                // 모든 권한이 활성화되었으면 서비스 시작
                TimeCreditBackgroundService.startService(this)
            }
        }
        
        // 알람 권한이 부여되었는지 확인하고, 부여되었다면 플래그 초기화
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (alarmManager.canScheduleExactAlarms() && hasAlarmPermissionError()) {
                // 권한이 부여되었으므로 플래그 초기화
                clearAlarmPermissionError()
                Log.d(TAG, "Alarm permission granted, cleared error flag")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "${AppLog.LIFECYCLE} activity leaving foreground (user left or dialog) → background")
    }
    
    /**
     * 앱이 포그라운드일 때 Time Credit 정산을 실행합니다.
     * repeatOnLifecycle(STARTED) 내에서 호출되며, 앱이 화면에 보이는 동안 60초마다 실행되어 실시간 잔액 갱신을 합니다.
     */
    private fun runSettlementInForeground() {
        try {
            val timeCreditService = TimeCreditService(this)
            val result = timeCreditService.settleCredits()
            if (result.earnedSeconds > 0L) {
                Log.i(TAG, "${AppLog.CREDIT} foreground settlement → earned +${result.earnedSeconds}s (balance: ${result.newBalanceSeconds}s)")
                val earnedText = if (result.earnedSeconds >= 60) "${result.earnedSeconds / 60}분" else "${result.earnedSeconds}초"
                Toast.makeText(
                    this,
                    "당신의 인내로 ${earnedText}의 자유를 얻었습니다",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "${AppLog.CREDIT} foreground settlement failed → exception", e)
        }
    }

    /**
     * 서비스 시작 버튼의 활성화 상태를 권한에 따라 업데이트합니다.
     */
    private fun updateServiceButtonState() {
        if (::fabStartService.isInitialized) {
            val hasAllPermissions = checkAllPermissions()
            fabStartService.isEnabled = hasAllPermissions
            fabStartService.alpha = if (hasAllPermissions) 1.0f else 0.5f
        }
    }
    
    /**
     * 알람 권한 에러 발생 여부를 확인합니다.
     */
    private fun hasAlarmPermissionError(): Boolean {
        return prefs.getBoolean(KEY_ALARM_PERMISSION_ERROR, false)
    }
    
    /**
     * 알람 권한 에러 발생 플래그를 저장합니다.
     */
    private fun saveAlarmPermissionError() {
        prefs.edit().putBoolean(KEY_ALARM_PERMISSION_ERROR, true).apply()
    }
    
    /**
     * 알람 권한 에러 플래그를 초기화합니다.
     */
    private fun clearAlarmPermissionError() {
        prefs.edit().putBoolean(KEY_ALARM_PERMISSION_ERROR, false).apply()
    }
}

/**
 * ViewPager2 어댑터
 */
class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MainFragment()
            1 -> CreditFragment()
            2 -> SettingsFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}
