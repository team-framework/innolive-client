package com.framework.innolive.feature.live.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.framework.innolive.feature.live.direct.publish.AsyncMediaEncoder
import com.framework.innolive.feature.live.direct.publish.EncoderOutput

/** 녹화 인코더 출력 크기입니다. 긴 변을 [MAX_RECORDING_LONG_EDGE] 이하로 맞추고 짝수로 만듭니다. */
internal data class RecordingSize(val width: Int, val height: Int)

internal const val MAX_RECORDING_LONG_EDGE = 1920

internal fun recordingSizeFor(sourceWidth: Int, sourceHeight: Int): RecordingSize {
    require(sourceWidth > 0 && sourceHeight > 0)
    val longEdge = maxOf(sourceWidth, sourceHeight)
    val scale = if (longEdge > MAX_RECORDING_LONG_EDGE) MAX_RECORDING_LONG_EDGE.toDouble() / longEdge else 1.0
    fun even(value: Int) = (value - value % 2).coerceAtLeast(2)
    return RecordingSize(even((sourceWidth * scale).toInt()), even((sourceHeight * scale).toInt()))
}

/** 저장용이므로 송출보다 높은 비트레이트를 씁니다. 720p 6Mbps, 그보다 크면 10Mbps입니다. */
internal fun recordingVideoBitrateFor(size: RecordingSize): Int =
    if (size.width.toLong() * size.height <= 1280L * 720L) 6_000_000 else 10_000_000

internal const val RECORDING_AUDIO_BITRATE_BPS = 128_000
internal const val RECORDING_FPS = 30

/**
 * 녹화 영상용 H.264 인코더입니다. 입력 Surface를 받으며 [signalEndOfInputStream] 뒤 마지막 출력에서
 * [EncoderOutput.onEndOfStream]이 옵니다. 카메라가 멈춘 동안에도 마지막 프레임을 반복해 시간축을 유지합니다.
 */
internal class RecordingVideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrateBps: Int,
    output: EncoderOutput,
    codecFactory: (String) -> MediaCodec = MediaCodec::createEncoderByType,
) : AsyncMediaEncoder("recording-video", output, codecFactory) {
    lateinit var inputSurface: Surface
        private set

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps * 2)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        initializeOrClose {
            try {
                configure(format, MediaFormat.MIMETYPE_VIDEO_AVC, surfaceInput = true)
            } catch (_: Exception) {
                // 일부 기기는 High 프로파일 지정을 거부합니다. 기본 프로파일로 다시 설정합니다.
                format.removeKey(MediaFormat.KEY_PROFILE)
                format.removeKey(MediaFormat.KEY_LEVEL)
                configure(format, MediaFormat.MIMETYPE_VIDEO_AVC, surfaceInput = true)
            }
        }
    }

    override fun createSurface() {
        inputSurface = codec.createInputSurface()
    }

    /** 더 이상 Surface에 그리지 않을 때 부릅니다. 인코더 스레드에서 종료 상태 예외는 무시합니다. */
    fun signalEndOfInputStream() {
        handler.post { if (!isReleased) runCatching { codec.signalEndOfInputStream() } }
    }
}
