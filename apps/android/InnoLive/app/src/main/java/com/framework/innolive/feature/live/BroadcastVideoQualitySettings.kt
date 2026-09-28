package com.framework.innolive.feature.live

/** User choices are independent of the current camera's supported capture range. */
data class BroadcastVideoQualitySettings(
    val stabilizationEnabled: Boolean = true,
    val exposureEV: Float = 0f,
    val warmth: Float = 0f,
    val saturation: Float = 1f,
) {
    fun normalized() = copy(
        exposureEV = exposureEV.finiteOr(0f).coerceIn(-2f, 2f),
        warmth = warmth.finiteOr(0f).coerceIn(-1f, 1f),
        saturation = saturation.finiteOr(1f).coerceIn(0f, 2f),
    )

    fun resetAdjustments() = BroadcastVideoQualitySettings(stabilizationEnabled = stabilizationEnabled)
}

private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback

enum class VideoLookPreset(val exposureEV: Float, val warmth: Float, val saturation: Float) {
    VIVID(0f, -0.2f, 1.4f),
    BRIGHT(0.8f, 0.2f, 1.2f),
    WARM(0.2f, 0.6f, 1.1f);

    fun applyTo(settings: BroadcastVideoQualitySettings) = settings.copy(
        exposureEV = exposureEV,
        warmth = warmth,
        saturation = saturation,
    )

    fun matches(settings: BroadcastVideoQualitySettings): Boolean =
        settings.exposureEV == exposureEV && settings.warmth == warmth && settings.saturation == saturation
}
