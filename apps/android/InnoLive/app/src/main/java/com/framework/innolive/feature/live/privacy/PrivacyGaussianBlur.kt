package com.framework.innolive.feature.live.privacy

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/** Separable Gaussian convolution, with clamped edges and a normalized fixed-point kernel. */
internal object PrivacyGaussianBlur {
    fun apply(pixels: IntArray, width: Int, height: Int, sigma: Double): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height && sigma > 0 && sigma.isFinite())
        val radius = ceil(3 * sigma).toInt()
        val weights = DoubleArray(radius * 2 + 1) { index ->
            val offset = index - radius
            exp(-offset.toDouble() * offset / (2 * sigma * sigma))
        }
        val sum = weights.sum()
        val kernel = IntArray(weights.size) { (weights[it] / sum * 65536).roundToInt() }
        kernel[radius] += 65536 - kernel.sum()
        val horizontal = IntArray(pixels.size)
        val output = IntArray(pixels.size)
        for (vertical in listOf(false, true)) {
            val source = if (vertical) horizontal else pixels
            val target = if (vertical) output else horizontal
            for (y in 0 until height) for (x in 0 until width) {
                var alpha = 0; var red = 0; var green = 0; var blue = 0
                for (index in kernel.indices) {
                    val offset = index - radius
                    val position = if (vertical) (y + offset).coerceIn(0, height - 1) * width + x
                        else y * width + (x + offset).coerceIn(0, width - 1)
                    val pixel = source[position]
                    val weight = kernel[index]
                    alpha += (pixel ushr 24) * weight
                    red += ((pixel ushr 16) and 255) * weight
                    green += ((pixel ushr 8) and 255) * weight
                    blue += (pixel and 255) * weight
                }
                target[y * width + x] = (((alpha + 32768) ushr 16) shl 24) or
                    (((red + 32768) ushr 16) shl 16) or
                    (((green + 32768) ushr 16) shl 8) or ((blue + 32768) ushr 16)
            }
        }
        return output
    }
}
