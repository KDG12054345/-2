package com.faust.services

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.faust.data.utils.PreferenceManager
import com.faust.domain.StrictModeService

/**
 * 기기 관리자 권한 관리 및 앱 삭제 방지
 * 
 * 역할: 엄격모드 활성화 시 앱 삭제를 방지하는 DeviceAdminReceiver입니다.
 * 처리: 기기 관리자 활성화, 엄격모드 활성 시 기기 관리자 비활성화 차단
 */
class FaustAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "FaustAdminReceiver"

        /**
         * 기기 관리자 컴포넌트 이름을 반환합니다.
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, FaustAdminReceiver::class.java)
        }

        /**
         * 기기 관리자가 활성화되어 있는지 확인합니다.
         */
        fun isAdminActive(context: Context): Boolean {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return devicePolicyManager.isAdminActive(getComponentName(context))
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        // 엄격모드가 활성화되어 있으면 비활성화 차단
        if (StrictModeService.isStrictActive(context)) {
            Log.d(TAG, "Cannot disable device admin while strict mode is active")
            return context.getString(com.faust.R.string.device_admin_disable_blocked)
        }
        return super.onDisableRequested(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device admin disabled")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d(TAG, "Lock task mode entering: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.d(TAG, "Lock task mode exiting")
    }
}
