package com.framework.innolive.feature.live

import android.content.Context
import com.framework.innolive.feature.live.privacy.PrivacyBroadcastBlur

/**
 * 온디바이스 AI의 얼굴·번호판 블러 강도와 가림 영역 너비입니다. 서버 AI 슬라이더와 같은 눈금(블러 24, 너비 8px)을
 * 쓰지만, 블러는 지금 온디바이스 강도보다 약해지지 않고 너비는 마스크 1칸 아래로 줄지 않습니다. 기기에 저장합니다.
 */
data class OnDeviceMosaicSettings(
    val blurStrength: Int = PrivacyBroadcastBlur.DEFAULT_STRENGTH,
    val maskMargin: Int = MosaicConfig.DEFAULT_MASK_MARGIN,
) {
    fun normalized() = copy(
        blurStrength = blurStrength.coerceIn(BLUR_STRENGTH_RANGE),
        maskMargin = maskMargin.coerceIn(MASK_MARGIN_RANGE),
    )

    /** 영상 조절 시트의 모자이크 구역이 보여 주는 값입니다. */
    fun toMosaicConfig() = MosaicConfig(blurRadius = blurStrength.toDouble(), maskMargin = maskMargin)

    companion object {
        val BLUR_STRENGTH_RANGE = PrivacyBroadcastBlur.DEFAULT_STRENGTH..PrivacyBroadcastBlur.MAX_STRENGTH
        /** 8px 아래는 어차피 마스크 1칸이라 슬라이더도 기본값부터 시작합니다. */
        val MASK_MARGIN_RANGE = MosaicConfig.DEFAULT_MASK_MARGIN..MosaicConfig.MASK_MARGIN_RANGE.last

        /** 시트가 돌려준 값입니다. 기본값으로 되돌리기는 null 필드로 오며, 서버 기본값 24·8px가 곧 지금 강도와 1칸입니다. */
        fun from(config: MosaicConfig) =
            OnDeviceMosaicSettings(config.effectiveBlurRadius, config.effectiveMaskMargin).normalized()
    }
}

class OnDeviceMosaicPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("live_on_device_mosaic", Context.MODE_PRIVATE)

    fun load() = OnDeviceMosaicSettings(
        blurStrength = read("blur_strength", PrivacyBroadcastBlur.DEFAULT_STRENGTH),
        maskMargin = read("mask_margin", MosaicConfig.DEFAULT_MASK_MARGIN),
    ).normalized()

    fun save(settings: OnDeviceMosaicSettings) {
        val value = settings.normalized()
        preferences.edit()
            .putInt("blur_strength", value.blurStrength)
            .putInt("mask_margin", value.maskMargin)
            .apply()
    }

    // 저장 형식이 바뀌었거나 깨진 값은 기본값으로 읽어 카메라 시작을 막지 않습니다.
    private fun read(key: String, fallback: Int) =
        runCatching { preferences.getInt(key, fallback) }.getOrDefault(fallback)
}
