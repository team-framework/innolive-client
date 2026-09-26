package com.framework.innolive.feature.live.privacy

import kotlin.math.ceil
import kotlin.math.floor

/** Same per-channel math and empty-mask protection as the portable Kotlin reference, via NEON. */
internal object PrivacyNativeSegmentation {
    fun instanceMasks(detections: List<PrivacySegmentation.Detection>, prototypes: FloatArray): List<PrivacySegmentation.InstanceMask> {
        require(detections.size <= 100)
        val coefficients = FloatArray(detections.size * 32)
        val bounds = IntArray(detections.size * 4)
        detections.forEachIndexed { index, detection ->
            require(detection.coefficients.size == 32)
            detection.coefficients.copyInto(coefficients, index * 32)
            val box = detection.box
            require(listOf(box.left, box.top, box.right, box.bottom).all(Float::isFinite))
            bounds[index * 4] = floor(box.left / 4f).toInt().coerceIn(0, 160)
            bounds[index * 4 + 1] = floor(box.top / 4f).toInt().coerceIn(0, 160)
            bounds[index * 4 + 2] = ceil(box.right / 4f).toInt().coerceIn(0, 160)
            bounds[index * 4 + 3] = ceil(box.bottom / 4f).toInt().coerceIn(0, 160)
        }
        val masks = PrivacyNativePixels.computeInstanceMasks(coefficients, bounds, prototypes)
        return detections.mapIndexed { index, detection -> PrivacySegmentation.InstanceMask(detection, masks[index]) }
    }
}
