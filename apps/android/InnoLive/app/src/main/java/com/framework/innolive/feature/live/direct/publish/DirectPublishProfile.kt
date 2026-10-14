package com.framework.innolive.feature.live.direct.publish

import com.framework.innolive.feature.live.direct.DirectPublishResolution

internal enum class PublishOrientation { LANDSCAPE, PORTRAIT }

/**
 * 직접 송출 인코더 설정입니다. 세션의 `broadcast_resolution`과 같은 해상도로 보내야
 * 서버 차감 배수와 실제 송출이 일치합니다.
 */
internal data class DirectPublishProfile(
    val resolution: DirectPublishResolution,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateBps: Int,
    val keyFrameIntervalSeconds: Int,
    val audioSampleRate: Int,
    val audioChannels: Int,
    val audioBitrateBps: Int,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "인코더 해상도는 짝수여야 합니다." }
        require(fps in 1..60) { "fps 범위를 벗어났습니다." }
        require(keyFrameIntervalSeconds in 1..4) { "키프레임 간격은 플랫폼 권장 범위(4초 이하)여야 합니다." }
        require(audioChannels in 1..2) { "오디오 채널은 1 또는 2여야 합니다." }
    }

    val isStereo: Boolean get() = audioChannels == 2

    companion object {
        private const val FPS = 30
        private const val KEY_FRAME_INTERVAL_SECONDS = 2
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_BITRATE_BPS = 128_000

        /**
         * YouTube 권장 비트레이트(720p30 최대 4Mbps, 1080p30 최대 6Mbps) 안에서 정했습니다.
         * 치지직 권장 상한(8Mbps)보다 낮아 같은 인코딩을 두 플랫폼에 함께 보낼 수 있습니다.
         */
        fun of(resolution: DirectPublishResolution, orientation: PublishOrientation): DirectPublishProfile {
            val (longEdge, shortEdge, bitrate) = when (resolution) {
                DirectPublishResolution.HD_720 -> Triple(1280, 720, 4_000_000)
                DirectPublishResolution.FHD -> Triple(1920, 1080, 6_000_000)
            }
            val (width, height) = when (orientation) {
                PublishOrientation.LANDSCAPE -> longEdge to shortEdge
                PublishOrientation.PORTRAIT -> shortEdge to longEdge
            }
            return DirectPublishProfile(
                resolution = resolution,
                width = width,
                height = height,
                fps = FPS,
                videoBitrateBps = bitrate,
                keyFrameIntervalSeconds = KEY_FRAME_INTERVAL_SECONDS,
                audioSampleRate = AUDIO_SAMPLE_RATE,
                audioChannels = 1,
                audioBitrateBps = AUDIO_BITRATE_BPS,
            )
        }
    }
}

/** 원본 비율을 유지한 채 인코더 화면 안에 맞추는 영역입니다. 남는 곳은 검은 여백입니다. */
internal data class FitViewport(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun fitViewport(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): FitViewport {
    require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
    // 정수 곱으로 비교해 같은 비율에서 반올림 오차로 여백이 생기지 않게 합니다.
    return if (sourceWidth.toLong() * targetHeight >= sourceHeight.toLong() * targetWidth) {
        val height = (targetWidth.toLong() * sourceHeight / sourceWidth).toInt().coerceIn(1, targetHeight)
        FitViewport(0, (targetHeight - height) / 2, targetWidth, height)
    } else {
        val width = (targetHeight.toLong() * sourceWidth / sourceHeight).toInt().coerceIn(1, targetWidth)
        FitViewport((targetWidth - width) / 2, 0, width, targetHeight)
    }
}
