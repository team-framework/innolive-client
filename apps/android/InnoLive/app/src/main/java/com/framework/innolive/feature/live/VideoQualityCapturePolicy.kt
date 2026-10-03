package com.framework.innolive.feature.live

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class VideoStabilizationStatus { INACTIVE, ACTIVE, UNSUPPORTED, PENDING }

data class VideoQualityCaptureState(
    val exposureSupported: Boolean = false,
    val minExposureEV: Float = 0f,
    val maxExposureEV: Float = 0f,
    val appliedExposureEV: Float = 0f,
    val stabilizationStatus: VideoStabilizationStatus = VideoStabilizationStatus.PENDING,
)

/** Camera2 numeric values, kept free of Android dependencies for policy tests. */
object VideoQualityCapturePolicy {
    const val STABILIZATION_OFF = 0
    const val STABILIZATION_STANDARD = 1
    const val STABILIZATION_PREVIEW = 2

    fun stabilizationMode(enabled: Boolean, availableModes: Set<Int>, supportsPreview: Boolean): Int {
        if (!enabled) return STABILIZATION_OFF
        if (supportsPreview && STABILIZATION_PREVIEW in availableModes) return STABILIZATION_PREVIEW
        if (STABILIZATION_STANDARD in availableModes) return STABILIZATION_STANDARD
        return STABILIZATION_OFF
    }

    fun stabilizationStatus(enabled: Boolean, requestedMode: Int, activeMode: Int?): VideoStabilizationStatus =
        when {
            !enabled -> if (activeMode == null || activeMode == STABILIZATION_OFF) {
                VideoStabilizationStatus.INACTIVE
            } else VideoStabilizationStatus.PENDING
            requestedMode == STABILIZATION_OFF -> VideoStabilizationStatus.UNSUPPORTED
            activeMode == null -> VideoStabilizationStatus.PENDING
            activeMode == STABILIZATION_OFF -> VideoStabilizationStatus.UNSUPPORTED
            activeMode == requestedMode -> VideoStabilizationStatus.ACTIVE
            else -> VideoStabilizationStatus.PENDING
        }

    fun exposureIndices(minIndex: Int, maxIndex: Int, stepEV: Float): IntRange? {
        if (!stepEV.isFinite() || stepEV <= 0f || minIndex > maxIndex) return null
        val lower = maxOf(minIndex, ceil(-2.0 / stepEV - 0.000001).toInt())
        val upper = minOf(maxIndex, floor(2.0 / stepEV + 0.000001).toInt())
        return if (lower <= upper) lower..upper else null
    }

    fun exposureIndex(requestedEV: Float, minIndex: Int, maxIndex: Int, stepEV: Float): Int {
        val indices = exposureIndices(minIndex, maxIndex, stepEV) ?: return 0
        val ev = if (requestedEV.isFinite()) requestedEV.coerceIn(-2f, 2f) else 0f
        return (ev / stepEV).roundToInt().coerceIn(indices.first, indices.last)
    }
}
