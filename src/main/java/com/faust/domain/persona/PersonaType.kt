package com.faust.domain.persona

/**
 * 페르소나 타입 Enum
 * 각 페르소나는 고유한 성격과 피드백 패턴을 가집니다.
 */
enum class PersonaType {
    /**
     * 불규칙 자극: 빠르고 강렬한 피드백
     */
    STREET,
    
    /**
     * 부드러운 성찰: 느리고 차분한 피드백
     */
    CALM,
    
    /**
     * 규칙적 압박: 일정한 리듬의 피드백
     */
    DIPLOMATIC,
    
    /**
     * 편안한 위로: 부드럽고 편안한 피드백
     */
    COMFORTABLE
}
