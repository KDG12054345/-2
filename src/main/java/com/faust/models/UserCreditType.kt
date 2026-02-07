package com.faust.models

/**
 * TimeCredit 시스템의 사용자 절제 프로필을 정의합니다.
 * ratio는 절제 시간(분) 대비 크레딧 환산 비율입니다.
 * 예: LIGHT(4) → 4분 절제 = 1분 크레딧
 */
enum class UserCreditType(val ratio: Int, val displayName: String) {
    LIGHT(4, "Light"),      // 4:1 비율 - 단순 습관 교정용
    PRO(6, "Pro"),          // 6:1 비율 - 딥워크 및 고도의 집중용
    DETOX(10, "Detox")      // 10:1 비율 - 중독 치료 및 도파민 수용체 회복용
}
