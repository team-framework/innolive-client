package com.framework.innolive.feature.live

import kotlin.math.roundToInt

/** 조정 전 원본에서 하드웨어 노출을 추정한 뒤, 송출과 같은 색차 테이블을 사용합니다. */
internal class VideoLookPreviewPixelTransform(
    warmth: Float,
    saturation: Float,
    targetExposureEV: Float,
    appliedExposureEV: Float,
) {
    private val color = VideoColorTransform(warmth, saturation)
    private val exposure = previewExposureLookup(targetExposureEV, appliedExposureEV)
    private val neutralExposure = exposure.indices.all { exposure[it] == it }

    fun pixel(y: Int, u: Int, v: Int): Int {
        var sourceY = y
        var sourceU = u
        var sourceV = v
        if (!neutralExposure) {
            val r = exposure[red(y, v)]
            val g = exposure[green(y, u, v)]
            val b = exposure[blue(y, u)]
            // WebRTC YUV 셰이더의 역변환인 BT.601 제한 범위입니다. 하드웨어 노출이 송출 처리보다
            // 먼저인 것처럼, 노출은 색온도·채도보다 먼저 적용합니다.
            sourceY = (16f + 0.256788f * r + 0.504129f * g + 0.097906f * b).byteValue()
            sourceU = (128f - 0.148223f * r - 0.290993f * g + 0.439216f * b).byteValue()
            sourceV = (128f + 0.439216f * r - 0.367788f * g - 0.071427f * b).byteValue()
        }
        val adjustedU = color.u[sourceU]
        val adjustedV = color.v[sourceV]
        return (0xff shl 24) or (red(sourceY, adjustedV) shl 16) or
            (green(sourceY, adjustedU, adjustedV) shl 8) or blue(sourceY, adjustedU)
    }

    private fun red(y: Int, v: Int) = (1.16438f * y + 1.59603f * v - 0.874202f * 255f).byteValue()
    private fun green(y: Int, u: Int, v: Int) =
        (1.16438f * y - 0.391762f * u - 0.812968f * v + 0.531668f * 255f).byteValue()
    private fun blue(y: Int, u: Int) = (1.16438f * y + 2.01723f * u - 1.08563f * 255f).byteValue()
    private fun Float.byteValue() = roundToInt().coerceIn(0, 255)
}
