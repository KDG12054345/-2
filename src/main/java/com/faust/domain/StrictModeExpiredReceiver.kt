package com.faust.domain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * [시스템 진입점: 시간 기반 진입점]
 * 
 * 역할: AlarmManager에 의해 엄격모드 만료 시간에 시스템이 브로드캐스트를 던져 엄격모드를 자동으로 해제하는 지점입니다.
 * 트리거: AlarmManager 트리거 (엄격모드 만료 시간)
 * 처리: 엄격모드 자동 해제
 * 
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class StrictModeExpiredReceiver : BroadcastReceiver() {
    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: AlarmManager 트리거를 수신하여 엄격모드를 자동으로 해제합니다.
     * 트리거: "com.faust.STRICT_MODE_EXPIRED" 액션
     * 처리: StrictModeService.disableStrictProtection() 호출
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.faust.STRICT_MODE_EXPIRED") {
            Log.d(TAG, "Strict mode expired, disabling protection")
            StrictModeService.disableStrictProtection(context)
        }
    }

    companion object {
        private const val TAG = "StrictModeExpiredReceiver"
    }
}
