package com.faust.utils

import android.net.Uri
import android.util.Log

/**
 * URL 입력 검증 및 정규화 유틸리티입니다.
 * 
 * 주요 기능:
 * - 사용자 입력이 유효한 URL/도메인인지 검증
 * - 프로토콜(http/https), www., m. 접두어 제거
 * - 경로/쿼리 제거 후 핵심 호스트(도메인)만 추출
 * - 검색어/키워드와 URL 구분 ("Search Query Trap" 방지)
 */
object UrlNormalizer {
    private const val TAG = "UrlNormalizer"
    
    /**
     * URL 정규화 결과를 나타내는 sealed class.
     */
    sealed class NormalizeResult {
        /**
         * 정규화 성공. 정규화된 도메인을 포함합니다.
         * @param domain 정규화된 도메인 (예: "youtube.com")
         */
        data class Success(val domain: String) : NormalizeResult()
        
        /**
         * 정규화 실패. 사용자에게 표시할 메시지를 포함합니다.
         * @param userMessage 사용자에게 표시할 오류 메시지
         */
        data class InvalidFormat(
            val userMessage: String = "Please enter a valid website address."
        ) : NormalizeResult()
    }
    
    /** 제거할 호스트 접두어 목록 */
    private val HOST_PREFIXES_TO_REMOVE = listOf("www.", "m.", "mobile.")
    
    /** 도메인 형식 검증 정규식 (최소 하나의 . 포함, 허용 문자만) */
    private val DOMAIN_PATTERN = Regex(
        "^([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"
    )
    
    /**
     * 사용자 입력을 검증하고 정규화된 도메인을 반환합니다.
     * 
     * 처리 과정:
     * 1. null/blank 체크
     * 2. 스킴이 없으면 "https://" 추가하여 Uri 파싱
     * 3. host 추출 (없으면 검색어로 간주하여 실패)
     * 4. www./m. 등 접두어 제거
     * 5. 도메인 형식 검증
     * 6. 소문자 정규화 후 반환
     * 
     * @param input 사용자 입력 문자열
     * @return NormalizeResult.Success(domain) 또는 NormalizeResult.InvalidFormat(message)
     */
    fun normalize(input: String?): NormalizeResult {
        // 1. null/blank 체크
        if (input.isNullOrBlank()) {
            return NormalizeResult.InvalidFormat()
        }
        
        val trimmed = input.trim()
        
        try {
            // 2. 스킴 추가 (없으면)
            val urlString = if (trimmed.contains("://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            
            // 3. Uri 파싱 및 host 추출
            val uri = Uri.parse(urlString)
            val host = uri.host
            
            if (host.isNullOrBlank()) {
                // host가 없으면 검색어/키워드로 간주
                Log.d(TAG, "No host found in input: $trimmed")
                return NormalizeResult.InvalidFormat()
            }
            
            // 4. 접두어 제거 (www., m., mobile.)
            var normalizedHost = host.lowercase()
            for (prefix in HOST_PREFIXES_TO_REMOVE) {
                if (normalizedHost.startsWith(prefix)) {
                    normalizedHost = normalizedHost.removePrefix(prefix)
                    break // 하나만 제거
                }
            }
            
            // 5. 도메인 형식 검증
            if (!isValidDomainFormat(normalizedHost)) {
                Log.d(TAG, "Invalid domain format: $normalizedHost")
                return NormalizeResult.InvalidFormat()
            }
            
            // 6. 성공
            Log.d(TAG, "Normalized: $input → $normalizedHost")
            return NormalizeResult.Success(normalizedHost)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error normalizing URL: $input", e)
            return NormalizeResult.InvalidFormat()
        }
    }
    
    /**
     * 도메인 형식이 유효한지 검증합니다.
     * - 최소 하나의 . 포함
     * - 허용된 문자만 포함 (알파벳, 숫자, 하이픈)
     * - TLD가 2자 이상
     */
    private fun isValidDomainFormat(domain: String): Boolean {
        if (domain.isBlank()) return false
        if (!domain.contains('.')) return false
        return DOMAIN_PATTERN.matches(domain)
    }
    
    /**
     * 입력이 URL 형식인지 간단히 확인합니다 (정규화하지 않음).
     * UI에서 실시간 피드백용으로 사용할 수 있습니다.
     */
    fun isLikelyUrl(input: String?): Boolean {
        if (input.isNullOrBlank()) return false
        val trimmed = input.trim()
        
        // 명확한 URL 패턴
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return true
        }
        
        // 도메인 패턴 (최소 하나의 . 포함)
        return trimmed.contains('.') && !trimmed.contains(' ')
    }
    
    /**
     * 정규화된 도메인으로 두 URL이 같은 사이트인지 비교합니다.
     * 
     * @param url1 첫 번째 URL
     * @param url2 두 번째 URL
     * @return 두 URL이 같은 도메인이면 true
     */
    fun isSameDomain(url1: String?, url2: String?): Boolean {
        val result1 = normalize(url1)
        val result2 = normalize(url2)
        
        return result1 is NormalizeResult.Success &&
               result2 is NormalizeResult.Success &&
               result1.domain == result2.domain
    }
    
    /**
     * URL에서 도메인만 추출합니다 (실패 시 null).
     * normalize()의 간편 버전입니다.
     */
    fun extractDomain(url: String?): String? {
        return when (val result = normalize(url)) {
            is NormalizeResult.Success -> result.domain
            is NormalizeResult.InvalidFormat -> null
        }
    }
}
