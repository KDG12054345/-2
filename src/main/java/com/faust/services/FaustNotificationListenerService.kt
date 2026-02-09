package com.faust.services

import android.service.notification.NotificationListenerService

/**
 * MediaSessionManager.getActiveSessions() 사용을 위해 필요한 알림 리스너 서비스.
 * 사용자가 설정 > 알림 액세스에서 "Faust"를 활성화하면 정밀 오디오 감지(재생 중 앱 식별)가 가능해집니다.
 * 알림 자체를 가로채거나 표시하지 않으며, 등록 목적만 있습니다.
 */
class FaustNotificationListenerService : NotificationListenerService() {

    // onNotificationPosted 등은 오버라이드하지 않음 — 알림 처리 불필요
}
