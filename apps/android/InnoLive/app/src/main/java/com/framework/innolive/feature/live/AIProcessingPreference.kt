package com.framework.innolive.feature.live

import android.content.Context

/** The selected processing location is distinct from the server's confirmed anonymization state. */
internal class AIProcessingPreference(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("live_ai_processing", Context.MODE_PRIVATE)

    var onDevice: Boolean
        get() = preferences.getBoolean("on_device", false)
        set(value) { preferences.edit().putBoolean("on_device", value).apply() }
}
