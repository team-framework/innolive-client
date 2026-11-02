package com.framework.innolive.feature.live

import android.content.Context

/** 선택한 처리 위치는 서버가 확인한 비식별화 상태와 별개입니다. */
internal class AIProcessingPreference(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("live_ai_processing", Context.MODE_PRIVATE)

    var onDevice: Boolean
        get() = preferences.getBoolean("on_device", false)
        set(value) { preferences.edit().putBoolean("on_device", value).apply() }
}
