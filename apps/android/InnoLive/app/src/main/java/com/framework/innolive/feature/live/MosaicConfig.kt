package com.framework.innolive.feature.live

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 서버 AI 세션의 얼굴·번호판 블러 강도와 영역입니다(`media.mosaic_config`). null 필드는 서버 AI 기본값이며
 * 0과 다릅니다. 새 세션은 모두 기본값으로 시작합니다.
 */
data class MosaicConfig(
    val blurRadius: Double? = null,
    val pixelSize: Int? = null,
    val maskMargin: Int? = null,
) {
    val effectiveBlurRadius: Int get() = blurRadius?.roundToInt() ?: DEFAULT_BLUR_RADIUS
    val effectiveMaskMargin: Int get() = maskMargin ?: DEFAULT_MASK_MARGIN

    /** 슬라이더 값을 서버 허용 범위로 맞춥니다. null은 기본값으로 되돌립니다. pixel_size는 바꾸지 않고 유지합니다. */
    fun adjusted(blurRadius: Int?, maskMargin: Int?): MosaicConfig = copy(
        blurRadius = blurRadius?.coerceIn(BLUR_RADIUS_RANGE)?.toDouble(),
        maskMargin = maskMargin?.coerceIn(MASK_MARGIN_RANGE),
    )

    /** `PUT /sessions/{id}/mosaic-config` 본문입니다. 본문이 세션 설정 전체이므로 기본값 필드는 뺍니다. */
    fun toJson(): JSONObject = JSONObject().apply {
        blurRadius?.let { put("blur_radius", it) }
        pixelSize?.let { put("pixel_size", it) }
        maskMargin?.let { put("mask_margin", it) }
    }

    companion object {
        const val DEFAULT_BLUR_RADIUS = 24
        const val DEFAULT_MASK_MARGIN = 8
        /** 서버는 blur_radius를 0 초과 64 이하로 받습니다. 슬라이더는 정수 1~64만 씁니다. */
        val BLUR_RADIUS_RANGE = 1..64
        val MASK_MARGIN_RANGE = 0..64
    }
}

/** 세션 응답의 `media.mosaic_config`입니다. 필드가 없으면 null이며, 알 수 없는 형식의 값은 기본값으로 읽습니다. */
internal fun parseMosaicConfig(response: JSONObject): MosaicConfig? {
    val config = response.optJSONObject("media")?.optJSONObject("mosaic_config") ?: return null
    fun number(key: String): Number? = config.opt(key) as? Number
    return MosaicConfig(
        blurRadius = number("blur_radius")?.toDouble()?.takeIf { it.isFinite() && it > 0 },
        pixelSize = number("pixel_size")?.toInt()?.takeIf { it > 0 },
        maskMargin = number("mask_margin")?.toInt()?.takeIf { it >= 0 },
    )
}
