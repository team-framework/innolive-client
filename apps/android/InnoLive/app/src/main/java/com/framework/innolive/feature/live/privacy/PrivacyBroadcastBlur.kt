package com.framework.innolive.feature.live.privacy

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS `PrivacyModel.broadcastStyleBlur`와 같이 픽셀화 격자를 쓰지 않습니다. 프레임을 면적 평균으로
 * 축소하고, 가우시안 한 번으로 흐린 뒤 다시 확대합니다. 한 점에서 샘플링한 고정 24px 블록 격자는
 * 프레임마다 반짝이지만, 넓은 가우시안은 주변 전체를 평균합니다.
 * sigma는 짧은 변을 따르므로(1/15, 전체 해상도에서 최소 48) 720p와 1080p의 강도가 같습니다.
 * Android는 1/4 해상도에서 블러를 적용하므로, 축소된 sigma는 그 값의 4분의 1입니다.
 */
internal object PrivacyBroadcastBlur {
    const val REDUCTION = 4
    const val MINIMUM_SIGMA = 48
    const val SIGMA_PER_SHORT_SIDE = 1.0 / 15
    /** 영상 조절의 온디바이스 블러 강도 눈금입니다. 24가 위 기본 sigma이고, 그보다 약하게는 내리지 않습니다. */
    const val DEFAULT_STRENGTH = 24
    /** 기본의 두 배까지입니다. 1080p 축소 커널 반지름이 54에서 108탭으로 늘어납니다. */
    const val MAX_STRENGTH = 48
    /** 강도는 24·30·36·42·48 다섯 단계입니다. GPU 블러 셰이더는 sigma마다 하나씩 컴파일되므로
     * 해상도당 셰이더를 다섯 개로 묶고, 송출 중 새 셰이더 컴파일도 단계가 바뀔 때만 일어나게 합니다. */
    const val STRENGTH_STEP = 6
    /** HWUI `Blur::convertRadiusToSigma`: 양수 반경에서 sigma = 0.57735 * radius + 0.5입니다. */
    private const val RENDER_EFFECT_RADIUS_SCALE = 0.57735f

    /**
     * iOS와 같은 프레임별 전체 해상도 가우시안 sigma에 온디바이스 강도를 곱한 값입니다.
     * 강도는 기본값 아래로 내려가지 않으므로, 블러가 iOS보다 약해지지 않습니다.
     */
    fun sigma(width: Int, height: Int, strength: Int = DEFAULT_STRENGTH): Int {
        require(width > 0 && height > 0)
        val base = max(MINIMUM_SIGMA, (min(width, height) * SIGMA_PER_SHORT_SIDE).roundToInt())
        return (base * strengthLevel(strength).toDouble() / DEFAULT_STRENGTH).roundToInt()
    }

    /** 강도를 기본~최대 범위의 가장 가까운 단계로 맞춥니다. */
    fun strengthLevel(strength: Int): Int {
        val steps = ((strength.coerceIn(DEFAULT_STRENGTH, MAX_STRENGTH) - DEFAULT_STRENGTH).toDouble() / STRENGTH_STEP).roundToInt()
        return DEFAULT_STRENGTH + steps * STRENGTH_STEP
    }

    /** 블러를 실행하는 축소 해상도에서의 sigma입니다. */
    fun reducedSigma(sigma: Int): Double = sigma.toDouble() / REDUCTION

    fun reducedSize(size: Int): Int = max(1, ceil(size / REDUCTION.toDouble()).toInt())

    fun kernelRadius(sigma: Double): Int = ceil(3 * sigma).toInt()

    /** HWUI가 전체 해상도 sigma로 변환하는 `RenderEffect.createBlurEffect` 반경입니다. */
    fun renderEffectRadius(sigma: Int): Float = (sigma - 0.5f) / RENDER_EFFECT_RADIUS_SCALE

    /** 모든 탭을 펼친 분리형 가우시안 프래그먼트 셰이더입니다. GLSL ES 1.0은 상수 반복 범위가 필요합니다. */
    fun loopGaussianShader(radius: Int, sigma: Double): String {
        require(radius > 0 && sigma > 0)
        return "uniform sampler2D tex0; uniform vec2 stepSize; void main(){ vec4 value=vec4(0.0); float sum=0.0;" +
            " for(int i=-$radius;i<=$radius;i++){ float w=exp(-float(i*i)/${number(2 * sigma * sigma)});" +
            " value+=texture2D(tex0,uv+float(i)*stepSize)*w; sum+=w; } gl_FragColor=value/sum; }"
    }

    /** 이웃한 두 탭을 그 사이의 bilinear 조회 한 번으로 합치는 분리형 가우시안입니다. */
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
