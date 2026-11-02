package com.framework.innolive.feature.live.privacy

import kotlin.math.ceil
import kotlin.math.floor

/** 이식 가능한 Kotlin 기준 구현과 같은 채널별 계산과 빈 마스크 보호를 NEON으로 수행합니다. */
internal object PrivacyNativeSegmentation {
    fun instanceMasks(detections: List<PrivacySegmentation.Detection>, prototypes: FloatArray): List<PrivacySegmentation.InstanceMask> {
        return instanceMasks(detections) { coefficients,bounds ->
            PrivacyNativePixels.computeInstanceMasks(coefficients,bounds,prototypes)
        }
    }
    fun instanceMasks(detections: List<PrivacySegmentation.Detection>, prototypes: PrivacyValidatedPrototypes): List<PrivacySegmentation.InstanceMask> {
        return instanceMasks(detections,prototypes::masks)
    }
    private fun instanceMasks(detections: List<PrivacySegmentation.Detection>, compute: (FloatArray,IntArray)->Array<ByteArray>): List<PrivacySegmentation.InstanceMask> {
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
        val masks = compute(coefficients, bounds)
        return detections.mapIndexed { index, detection -> PrivacySegmentation.InstanceMask(detection, masks[index]) }
    }
}

/**
 * 두 번째 프로토타입 검사를 건너뛰는 데 쓰는 검증된 입력은 이 클래스만 만들 수 있습니다.
 * 배열은 직렬 AI 스레드에서 소비되며, 이후 외부에 노출되거나 변경되지 않습니다.
 */
internal class PrivacyValidatedPrototypes private constructor(private val values: FloatArray) {
    fun copyInto(buffer: java.nio.ByteBuffer) { buffer.asFloatBuffer().put(values) }
    fun masks(coefficients: FloatArray,bounds: IntArray): Array<ByteArray> =
        PrivacyNativePixels.computeInstanceMasks(coefficients,bounds,values,prototypesValidated=true)
    companion object {
        fun validate(values: FloatArray): PrivacyValidatedPrototypes {
            require(values.size==32*160*160 && PrivacyNativePixels.finiteFloats(values))
            return PrivacyValidatedPrototypes(values)
        }
    }
}
