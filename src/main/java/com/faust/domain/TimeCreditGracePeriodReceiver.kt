package com.faust.domain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.faust.R
import com.faust.services.TimeCreditBackgroundService

/**
 * TimeCredit Grace Period 알림을 수신하는 BroadcastReceiver입니다.
 * 기존 AlarmManager 패턴(DailyResetReceiver, StrictModeExpiredReceiver)을 재사용합니다.
 *
 * 보상 시간 종료 5분 전과 1분 전에 사용자에게 알림을 표시합니다.
 */
class TimeCreditGracePeriodReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimeCreditGracePeriod"
        private const val CHANNEL_ID = "time_credit_grace_period"
        private const val NOTIFICATION_ID_5MIN = 6001
        private const val NOTIFICATION_ID_1MIN = 6002
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Grace Period 알림 수신: action=${intent.action}")

        when (intent.action) {
            TimeCreditService.ACTION_5MIN_BEFORE -> {
                showNotification(
                    context,
                    "보상 시간 종료 임박",
                    "보상 시간이 5분 후 종료됩니다. 마무리 준비를 해주세요.",
                    NOTIFICATION_ID_5MIN
                )
            }
            TimeCreditService.ACTION_1MIN_BEFORE -> {
                showNotification(
                    context,
                    "보상 시간 종료 임박",
                    "보상 시간이 1분 후 종료됩니다!",
                    NOTIFICATION_ID_1MIN
                )
                TimeCreditBackgroundService.notifyGoldenTimeAlarm(context)
            }
        }
    }

    private fun showNotification(context: Context, title: String, message: String, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 채널 생성 (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "타임크레딧 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "보상 시간 종료 임박 알림"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
        Log.d(TAG, "Grace Period 알림 표시: $message")
    }
}
