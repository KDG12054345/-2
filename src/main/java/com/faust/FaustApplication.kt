package com.faust

import android.app.Application
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FaustApplication : Application() {
    val database by lazy { FaustDatabase.getDatabase(this) }

    /**
     * 앱 전역 코루틴 스코프.
     * BroadcastReceiver 등 컴포넌트 생명주기에 종속되지 않는 비동기 작업에 사용.
     * SupervisorJob: 하위 코루틴 실패가 다른 코루틴에 영향 없음.
     * Application 수명과 동일하므로 cancel() 호출 불필요.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferenceManager by lazy { PreferenceManager(this) }

    override fun onCreate() {
        super.onCreate()
        // 기본 설정: 테스트 모드 활성화 (최대 10개)
        if (preferenceManager.getTestModeMaxApps() == null) {
            preferenceManager.setTestModeMaxApps(10)
        }
        // Time Credit 저장 실패 복구
        if (preferenceManager.isPersistDirty()) {
            android.util.Log.w("FaustApplication", "이전 실행에서 Time Credit 저장 실패 감지. 현재 캐시값으로 덮어쓰기 시도.")
            preferenceManager.persistTimeCreditValues(synchronous = true)
            preferenceManager.clearPersistDirtyFlag()
        }
        val dumpFile = java.io.File(filesDir, "tc_emergency_dump.txt")
        if (dumpFile.exists()) {
            try {
                val content = dumpFile.readText()
                if (content.endsWith("\nEOF")) {
                    val dataLine = content.substringBefore("\nEOF")
                    val parts = dataLine.split(",").mapNotNull { part ->
                        val kv = part.split("=")
                        if (kv.size == 2) kv[0].trim() to kv[1].trim().toLongOrNull() else null
                    }
                    val map = parts.toMap()
                    val balance = map["balance"]
                    val accumulated = map["accumulated"]
                    val lastSync = map["lastSync"]
                    if (balance != null && accumulated != null) {
                        android.util.Log.w("FaustApplication", "비상 파일에서 Time Credit 복구: balance=$balance, accumulated=$accumulated")
                        preferenceManager.setTimeCreditBalanceSeconds(balance)
                        preferenceManager.setAccumulatedAbstentionSeconds(accumulated)
                    }
                    // Phase 3: 비상 덤프 확장 - lastSync 필드도 복구
                    if (lastSync != null) {
                        android.util.Log.w("FaustApplication", "비상 파일에서 lastSync 복구: lastSync=$lastSync")
                        preferenceManager.setTimeCreditLastSyncTime(lastSync)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FaustApplication", "비상 파일 복구 실패", e)
            } finally {
                dumpFile.delete()
            }
        }
    }
}
