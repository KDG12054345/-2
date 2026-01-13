package com.faust.domain.persona

/**
 * 페르소나 프로필 데이터 클래스
 * 각 페르소나의 고유한 설정을 캡슐화합니다.
 * 
 * @param promptText 사용자가 정확히 입력해야 할 문구
 * @param vibrationPattern 진동 패턴 (밀리초 단위: [진동시간, 대기시간, 진동시간, ...])
 * @param audioResourceId res/raw의 오디오 파일 리소스 ID (null일 수 있음)
 */
data class PersonaProfile(
    val promptText: String,
    val vibrationPattern: List<Long>,
    val audioResourceId: Int?
)
