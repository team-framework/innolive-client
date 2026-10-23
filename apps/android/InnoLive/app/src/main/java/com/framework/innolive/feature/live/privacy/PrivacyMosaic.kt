package com.framework.innolive.feature.live.privacy

/**
 * 온디바이스 가림 처리의 블러 강도·가림 영역 너비입니다. 영상 조절 화면과 같은 눈금(강도 24, 너비 송출 px)을 쓰고,
 * 프레임 해상도에 맞는 블러 sigma와 160 마스크 팽창 칸 수로 바꾸는 계산을 GPU·CPU 경로가 함께 씁니다.
 */
internal data class PrivacyMosaic(
    val blurStrength: Int = PrivacyBroadcastBlur.DEFAULT_STRENGTH,
    val maskMargin: Int = DEFAULT_MASK_MARGIN,
) {
    fun sigma(width: Int, height: Int): Int = PrivacyBroadcastBlur.sigma(width, height, blurStrength)

    fun edgeCells(width: Int, height: Int): Int = PrivacyMaskRenderer.edgeCells(width, height, maskMargin)

    companion object {
        /** 서버 AI 기본값과 같은 8px이며, 720p·1080p 모두 마스크 1칸입니다. */
        const val DEFAULT_MASK_MARGIN = 8
    }
}
