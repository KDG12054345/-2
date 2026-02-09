package com.faust.utils

import android.util.Log

/**
 * 앱 로그의 도메인 태그와 포맷 규칙.
 *
 * 로그캣에서 원인·결과를 빠르게 구분하려면:
 * - 메시지 앞에 도메인 접두어 사용: [Lifecycle], [Credit], [Blocking], [UI]
 * - 포맷: "원인(cause) → 결과(result)" 또는 "상황 → 조치"
 *
 * 예: Log.i(TAG, "${AppLog.CREDIT} session active → settleCredits skipped")
 */
object AppLog {
    const val LIFECYCLE = "[Lifecycle]"
    const val CREDIT = "[Credit]"
    const val BLOCKING = "[Blocking]"
    const val UI = "[UI]"
    const val CONSISTENCY = "[CONSISTENCY]"

    @JvmStatic
    fun d(tag: String, domain: String, cause: String, result: String) {
        Log.d(tag, "$domain $cause → $result")
    }

    @JvmStatic
    fun i(tag: String, domain: String, cause: String, result: String) {
        Log.i(tag, "$domain $cause → $result")
    }

    @JvmStatic
    fun w(tag: String, domain: String, cause: String, result: String) {
        Log.w(tag, "$domain $cause → $result")
    }
}
