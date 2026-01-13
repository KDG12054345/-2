package com.faust.domain.persona

/**
 * 피드백 모드 Enum
 * 기기 상태와 환경에 따라 결정되는 피드백 조합을 나타냅니다.
 */
enum class FeedbackMode {
    /**
     * 텍스트만 표시
     */
    TEXT,
    
    /**
     * 진동만 실행
     */
    VIBRATION,
    
    /**
     * 오디오만 재생
     */
    AUDIO,
    
    /**
     * 텍스트 + 진동
     */
    TEXT_VIBRATION,
    
    /**
     * 텍스트 + 오디오
     */
    TEXT_AUDIO,
    
    /**
     * 모든 피드백 (텍스트 + 진동 + 오디오)
     */
    ALL
}
