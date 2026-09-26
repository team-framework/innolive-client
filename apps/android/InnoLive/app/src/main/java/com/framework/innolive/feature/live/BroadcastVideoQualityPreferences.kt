package com.framework.innolive.feature.live

import android.content.Context

class BroadcastVideoQualityPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("broadcast_video_quality", Context.MODE_PRIVATE)

    fun load() = BroadcastVideoQualitySettings(
        stabilizationEnabled = read("stabilization", true),
        exposureEV = read("exposure_ev", 0f),
        warmth = read("warmth", 0f),
        saturation = read("saturation", 1f),
    ).normalized()

    fun save(settings: BroadcastVideoQualitySettings) {
        val value = settings.normalized()
        preferences.edit()
            .putBoolean("stabilization", value.stabilizationEnabled)
            .putFloat("exposure_ev", value.exposureEV)
            .putFloat("warmth", value.warmth)
            .putFloat("saturation", value.saturation)
            .apply()
    }

    // Ignore obsolete or corrupt stored types without preventing camera startup.
    private fun read(key: String, fallback: Float) =
        runCatching { preferences.getFloat(key, fallback) }.getOrDefault(fallback)

    private fun read(key: String, fallback: Boolean) =
        runCatching { preferences.getBoolean(key, fallback) }.getOrDefault(fallback)
}
