package com.framework.innolive.feature.live

import android.content.Context

// 사용자 선택만 저장합니다. 서버의 실제 적용 상태는 세션마다 다시 확인합니다.
internal class AnonymizationPreference(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("live_anonymization", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }
}
