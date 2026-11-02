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

    // 더 이상 쓰지 않거나 손상된 저장 타입은 카메라 시작을 막지 않고 무시합니다.
    private fun read(key: String, fallback: Float) =
        runCatching { preferences.getFloat(key, fallback) }.getOrDefault(fallback)

    private fun read(key: String, fallback: Boolean) =
        runCatching { preferences.getBoolean(key, fallback) }.getOrDefault(fallback)
}
