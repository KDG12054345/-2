package com.faust

import android.app.Application
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager

class FaustApplication : Application() {
    val database by lazy { FaustDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // 기본 설정: 테스트 모드 활성화 (최대 10개)
        val preferenceManager = PreferenceManager(this)
        if (preferenceManager.getTestModeMaxApps() == null) {
            preferenceManager.setTestModeMaxApps(10)
        }
    }
}
