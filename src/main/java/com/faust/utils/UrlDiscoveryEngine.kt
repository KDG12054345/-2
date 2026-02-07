package com.faust.utils

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 브라우저 주소창에서 URL을 추출하는 유틸리티 엔진입니다.
 * 
 * 주요 특징:
 * - 휴리스틱 기반 URL 바 탐색 (Resource ID에 의존하지 않음)
 * - Geometric-First 접근: 화면 상단 20% 내 EditText/editable 노드 우선
 * - Safe Early-Exit: 고신뢰도 후보 발견 시 즉시 순회 종료
 * - 메모리 안전: 모든 AccessibilityNodeInfo는 try-finally로 recycle()
 * 
 * @see <a href="https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo">AccessibilityNodeInfo</a>
 */
object UrlDiscoveryEngine {
    private const val TAG = "UrlDiscoveryEngine"
    
    // ===== 상수 정의 =====
    
    /** 재귀 순회 최대 깊이 (CPU 폭증 방지) */
    private const val MAX_DEPTH = 18
    
    /** 고신뢰도 후보 임계값 (이상이면 조기 종료) */
    private const val CONFIDENCE_THRESHOLD = 100
    
    /** 화면 상단 비율 (Top 20%) */
    private const val TOP_PERCENT_THRESHOLD = 0.20f
    
    // ===== 점수 상수 =====
    
    /** Top 20% 위치 가산점 */
    private const val SCORE_TOP_POSITION = 50
    
    /** EditText / AutoCompleteTextView 가산점 */
    private const val SCORE_EDIT_TEXT = 30
    
    /** TextView 가산점 */
    private const val SCORE_TEXT_VIEW = 10
    
    /** isEditable == true 가산점 */
    private const val SCORE_EDITABLE = 20
    
    /** URL 형식 텍스트 가산점 */
    private const val SCORE_URL_FORMAT = 30
    
    /** 비어 있지 않은 텍스트 가산점 */
    private const val SCORE_NON_EMPTY_TEXT = 10
    
    /** contentDescription 키워드 가산점 */
    private const val SCORE_CONTENT_DESC_KEYWORD = 10
    
    // ===== URL 패턴 =====
    
    /** URL 형식 정규식 (http/https 또는 도메인 패턴) */
    private val URL_PATTERN = Regex(
        "^(https?://)?([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(/.*)?$",
        RegexOption.IGNORE_CASE
    )
    
    /** contentDescription 키워드 (언어 중립 + 주요 언어) */
    private val CONTENT_DESC_KEYWORDS = listOf(
        "url", "address", "search", "주소", "검색", "アドレス", "地址"
    )
    
    // ===== 지원 브라우저 패키지 목록 =====
    
    /**
     * 알려진 브라우저 패키지 목록.
     * 이 목록에 포함된 패키지에서만 URL 추출을 시도합니다.
     */
    val KNOWN_BROWSERS = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.duckduckgo.mobile.android",
        "com.brave.browser",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.uc.browser.en",
        "com.yandex.browser",
        "com.naver.whale",
        "com.nhn.android.search"
    )
    
    /**
     * URL 후보 데이터 클래스.
     * AccessibilityNodeInfo 참조를 저장하지 않고 텍스트와 메타데이터만 저장합니다.
     */
    private data class UrlCandidate(
        val text: String,
        val depth: Int,
        val score: Int
    )
    
    // ===== 공개 API =====
    
    /**
     * 브라우저 주소창에서 URL을 추출합니다.
     * 
     * @param rootNode AccessibilityNodeInfo 루트 노드 (호출자가 recycle 책임)
     * @param packageName 현재 포그라운드 앱의 패키지명
     * @return 추출된 URL 문자열. 실패 시 빈 문자열 ""
     */
    fun extractUrl(rootNode: AccessibilityNodeInfo?, packageName: String): String {
        // 1. 보안: KNOWN_BROWSERS 체크
        if (packageName !in KNOWN_BROWSERS) {
            return ""
        }
        
        // 2. null 체크
        if (rootNode == null) {
            return ""
        }
        
        try {
            // 3. 루트 창 높이 계산 (Geometric 휴리스틱용)
            val rootRect = Rect()
            rootNode.getBoundsInScreen(rootRect)
            val rootWindowHeight = rootRect.height()
            
            if (rootWindowHeight <= 0) {
                Log.w(TAG, "Invalid root window height: $rootWindowHeight")
                return ""
            }
            
            // 4. 후보 수집
            val candidates = mutableListOf<UrlCandidate>()
            val tempRect = Rect() // GC 부담 감소를 위해 재사용
            
            collectUrlBarCandidates(
                node = rootNode,
                depth = 0,
                candidates = candidates,
                maxDepth = MAX_DEPTH,
                rootWindowHeight = rootWindowHeight,
                tempRect = tempRect
            )
            
            // 5. 후보 선정 (점수 높은 순 → 깊이 얕은 순)
            if (candidates.isEmpty()) {
                return ""
            }
            
            val sortedCandidates = candidates.sortedWith(
                compareByDescending<UrlCandidate> { it.score }
                    .thenBy { it.depth }
            )
            
            // 6. 유효한 URL 반환
            for (candidate in sortedCandidates) {
                val text = candidate.text.trim()
                if (text.isNotBlank() && isLikelyUrl(text)) {
                    Log.d(TAG, "URL extracted: $text (score=${candidate.score}, depth=${candidate.depth})")
                    return text
                }
            }
            
            // 7. URL 형식 아니어도 최고 점수 후보 반환 (폴백)
            val topCandidate = sortedCandidates.firstOrNull { it.text.isNotBlank() }
            if (topCandidate != null) {
                Log.d(TAG, "Fallback URL: ${topCandidate.text} (score=${topCandidate.score})")
                return topCandidate.text.trim()
            }
            
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting URL", e)
            return ""
        }
    }
    
    /**
     * 패키지가 알려진 브라우저인지 확인합니다.
     */
    fun isBrowser(packageName: String?): Boolean {
        return packageName != null && packageName in KNOWN_BROWSERS
    }
    
    // ===== 내부 구현 =====
    
    /**
     * UI 트리를 재귀 순회하며 URL 바 후보를 수집합니다.
     * 
     * @param node 현재 노드
     * @param depth 현재 깊이
     * @param candidates 후보 목록 (결과 저장용)
     * @param maxDepth 최대 깊이
     * @param rootWindowHeight 루트 창 높이 (Top 20% 계산용)
     * @param tempRect Rect 재사용 인스턴스 (GC 감소)
     * @return true = High-Confidence 후보 발견 (조기 종료), false = 계속 탐색
     */
    private fun collectUrlBarCandidates(
        node: AccessibilityNodeInfo?,
        depth: Int,
        candidates: MutableList<UrlCandidate>,
        maxDepth: Int,
        rootWindowHeight: Int,
        tempRect: Rect
    ): Boolean {
        // 종료 조건
        if (node == null || depth > maxDepth) {
            return false
        }
        
        try {
            // 현재 노드 정보 추출 (null-safe)
            val text = node.text?.toString() ?: ""
            val className = node.className?.toString() ?: ""
            val contentDescription = node.contentDescription?.toString() ?: ""
            val isEditable = node.isEditable
            
            // 위치 계산
            node.getBoundsInScreen(tempRect)
            val nodeTop = tempRect.top
            val isInTop20Percent = rootWindowHeight > 0 && 
                (nodeTop.toFloat() / rootWindowHeight) <= TOP_PERCENT_THRESHOLD
            
            // 점수 계산
            var score = 0
            
            // 1. 위치 점수 (Top 20%)
            if (isInTop20Percent) {
                score += SCORE_TOP_POSITION
            }
            
            // 2. 타입 점수
            when {
                className.contains("EditText", ignoreCase = true) ||
                className.contains("AutoCompleteTextView", ignoreCase = true) -> {
                    score += SCORE_EDIT_TEXT
                }
                className.contains("TextView", ignoreCase = true) -> {
                    score += SCORE_TEXT_VIEW
                }
            }
            
            // 3. editable 점수
            if (isEditable) {
                score += SCORE_EDITABLE
            }
            
            // 4. 텍스트 점수
            if (text.isNotBlank()) {
                score += SCORE_NON_EMPTY_TEXT
                if (isLikelyUrl(text)) {
                    score += SCORE_URL_FORMAT
                }
            }
            
            // 5. contentDescription 키워드 점수
            if (hasUrlKeyword(contentDescription)) {
                score += SCORE_CONTENT_DESC_KEYWORD
            }
            
            // 후보 추가 (최소 점수 이상만)
            if (score > 0 && (text.isNotBlank() || isEditable)) {
                candidates.add(UrlCandidate(text, depth, score))
                
                // Early-Exit: High-Confidence 발견 시 즉시 종료
                if (score >= CONFIDENCE_THRESHOLD && text.isNotBlank()) {
                    Log.d(TAG, "High-confidence candidate found: $text (score=$score)")
                    return true
                }
            }
            
            // 자식 노드 순회
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    try {
                        val found = collectUrlBarCandidates(
                            node = child,
                            depth = depth + 1,
                            candidates = candidates,
                            maxDepth = maxDepth,
                            rootWindowHeight = rootWindowHeight,
                            tempRect = tempRect
                        )
                        // 자식에서 High-Confidence 발견 시 상위로 전파
                        if (found) {
                            return true
                        }
                    } finally {
                        // 메모리 누수 방지: 반드시 recycle
                        child.recycle()
                    }
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting URL candidates at depth $depth", e)
            return false
        }
    }
    
    /**
     * 텍스트가 URL 형식인지 확인합니다.
     */
    private fun isLikelyUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        
        // 명확한 URL 패턴
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return true
        }
        
        // 도메인 패턴 (정규식)
        return URL_PATTERN.matches(trimmed)
    }
    
    /**
     * contentDescription에 URL 관련 키워드가 있는지 확인합니다.
     */
    private fun hasUrlKeyword(contentDescription: String): Boolean {
        if (contentDescription.isBlank()) return false
        val lower = contentDescription.lowercase()
        return CONTENT_DESC_KEYWORDS.any { keyword -> 
            lower.contains(keyword.lowercase()) 
        }
    }
}
