package com.framework.innolive.feature.live.privacy

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** iOS `PrivacyModel.broadcastStyleBlur`: no pixelation grid. The frame is reduced by area
 * averaging, blurred with one Gaussian, then scaled back. A fixed 24px block grid sampled from a
 * single point shimmers between frames; the wide Gaussian averages the whole neighbourhood instead.
 * Sigma follows the short side (1/15, at least 48 at full resolution) so 720p and 1080p keep the
 * same strength. Android blurs at 1/4 resolution, so the reduced sigma is a quarter of that. */
internal object PrivacyBroadcastBlur {
    const val REDUCTION = 4
    const val MINIMUM_SIGMA = 48
    const val SIGMA_PER_SHORT_SIDE = 1.0 / 15
    /** HWUI `Blur::convertRadiusToSigma`: sigma = 0.57735 * radius + 0.5 for a positive radius. */
    private const val RENDER_EFFECT_RADIUS_SCALE = 0.57735f

    /** Full-resolution Gaussian sigma for a frame, as on iOS. */
    fun sigma(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        return max(MINIMUM_SIGMA, (min(width, height) * SIGMA_PER_SHORT_SIDE).roundToInt())
    }

    /** Sigma at the reduced resolution where the blur runs. */
    fun reducedSigma(width: Int, height: Int): Double = sigma(width, height).toDouble() / REDUCTION

    fun reducedSize(size: Int): Int = max(1, ceil(size / REDUCTION.toDouble()).toInt())

    fun kernelRadius(sigma: Double): Int = ceil(3 * sigma).toInt()

    /** `RenderEffect.createBlurEffect` radius that HWUI converts to the full-resolution sigma. */
    fun renderEffectRadius(width: Int, height: Int): Float =
        (sigma(width, height) - 0.5f) / RENDER_EFFECT_RADIUS_SCALE

    /** Separable Gaussian fragment shader with every tap unrolled; GLSL ES 1.0 needs constant bounds. */
    fun loopGaussianShader(radius: Int, sigma: Double): String {
        require(radius > 0 && sigma > 0)
        return "uniform sampler2D tex0; uniform vec2 stepSize; void main(){ vec4 value=vec4(0.0); float sum=0.0;" +
            " for(int i=-$radius;i<=$radius;i++){ float w=exp(-float(i*i)/${number(2 * sigma * sigma)});" +
            " value+=texture2D(tex0,uv+float(i)*stepSize)*w; sum+=w; } gl_FragColor=value/sum; }"
    }

    /** Separable Gaussian that merges neighbouring taps into one bilinear fetch between them. */
    fun pairedGaussianShader(radius: Int, sigma: Double, maxCore: Boolean = false): String {
        require(radius > 0 && sigma > 0)
        val weights = (0..radius).map { exp(-it * it / (2 * sigma * sigma)) }
        val total = weights[0] + 2 * (1..radius).sumOf { weights[it] }
        val code = StringBuilder("uniform sampler2D tex0${if (maxCore) ",tex1" else ""}; uniform vec2 stepSize;" +
            " void main(){ vec4 value=texture2D(tex0,uv)*${number(weights[0])};")
        var offset = 1
        while (offset <= radius) {
            val right = if (offset + 1 <= radius) offset + 1 else offset
            val weight = if (right == offset) weights[offset] else weights[offset] + weights[right]
            val position = if (right == offset) offset.toDouble() else
                (offset * weights[offset] + right * weights[right]) / weight
            code.append("value+=(texture2D(tex0,uv+${number(position)}*stepSize)+texture2D(tex0,uv-${number(position)}*stepSize))*${number(weight)};")
            offset = right + 1
        }
        code.append("vec4 blurred=value*${number(1 / total)}; gl_FragColor=${if (maxCore) "max(blurred,texture2D(tex1,uv))" else "blurred"};}")
        return code.toString()
    }

    private fun number(value: Double) = String.format(Locale.US, "%.9f", value)
}
