package com.framework.innolive.feature.live

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** WebRTC source 전에 색을 적용해 송출과 로컬 트랙이 같은 픽셀을 쓰게 합니다. */
internal class VideoColorFrameProcessor {
    private var cachedTransform = VideoColorTransform(0f, 1f)

    /** 변경이 없는 프레임의 빠른 경로를 포함해, 반환된 참조는 호출자가 소유합니다. */
    fun process(frame: VideoFrame, settings: BroadcastVideoQualitySettings): VideoFrame {
        val normalized = settings.normalized()
        if (!cachedTransform.matches(normalized.warmth, normalized.saturation)) {
            cachedTransform = VideoColorTransform(normalized.warmth, normalized.saturation)
        }
        val transform = cachedTransform
        if (transform.isNeutral) {
            frame.retain()
            return frame
        }

        val source = checkNotNull(frame.buffer.toI420()) { "I420 conversion failed" }
        try {
            val output = JavaI420Buffer.allocate(source.width, source.height)
            try {
                copyVideoPlane(source.dataY, source.strideY, output.dataY, output.strideY, source.width, source.height)
                val chromaWidth = (source.width + 1) / 2
                val chromaHeight = (source.height + 1) / 2
                transform.applyChroma(
                    source.dataU, source.strideU, output.dataU, output.strideU,
                    chromaWidth, chromaHeight, transform.u,
                )
                transform.applyChroma(
                    source.dataV, source.strideV, output.dataV, output.strideV,
                    chromaWidth, chromaHeight, transform.v,
                )
                return VideoFrame(output, frame.rotation, frame.timestampNs)
            } catch (exception: Throwable) {
                output.release()
                throw exception
            }
        } finally {
            source.release()
        }
    }
}

/**
 * 색차 조회 테이블로 캡처 해상도에서 RGB 변환과 부동소수점 연산을 피합니다.
 * 색온도는 파랑·빨강 색차를 반대 방향으로 옮기고, 채도는 중립점을 기준으로 둘 다 조절합니다.
 * 휘도는 하드웨어 노출 보정을 포함해 카메라 제어에 맡깁니다.
 */
internal class VideoColorTransform(private val warmth: Float, private val saturation: Float) {
    init {
        require(warmth.isFinite() && warmth in -1f..1f)
        require(saturation.isFinite() && saturation in 0f..2f)
    }

    val isNeutral = warmth == 0f && saturation == 1f
    val u = table(-VIDEO_WARMTH_CHROMA_OFFSET * warmth)
    val v = table(VIDEO_WARMTH_CHROMA_OFFSET * warmth)

    fun matches(warmth: Float, saturation: Float) = this.warmth == warmth && this.saturation == saturation

    private fun table(warmthOffset: Float) = IntArray(256) { value ->
        (128f + (value - 128f + warmthOffset) * saturation).roundToInt().coerceIn(0, 255)
    }

    fun applyChroma(
        source: ByteBuffer,
        sourceStride: Int,
        target: ByteBuffer,
        targetStride: Int,
        width: Int,
        height: Int,
        table: IntArray,
    ) {
        val sourceStart = source.position()
        val targetStart = target.position()
        repeat(height) { row ->
            val sourceRow = sourceStart + row * sourceStride
            val targetRow = targetStart + row * targetStride
            repeat(width) { column ->
                target.put(targetRow + column, table[source.get(sourceRow + column).toInt() and 0xff].toByte())
            }
        }
    }
}

internal const val VIDEO_WARMTH_CHROMA_OFFSET = 18f

internal fun copyVideoPlane(
    source: ByteBuffer,
    sourceStride: Int,
    target: ByteBuffer,
    targetStride: Int,
    width: Int,
    height: Int,
) {
    val input = source.duplicate()
    val output = target.duplicate()
    val sourceStart = input.position()
    val targetStart = output.position()
    repeat(height) { row ->
        val sourceRow = sourceStart + row * sourceStride
        input.limit(source.limit())
        input.position(sourceRow)
        input.limit(sourceRow + width)
        output.position(targetStart + row * targetStride)
        output.put(input)
    }
}

/** 조정에 실패하면 프레임을 의도적으로 버리며, 조정하지 않은 입력을 내보내지 않습니다. */
internal fun relayColorFrame(
    frame: VideoFrame,
    settings: BroadcastVideoQualitySettings,
    process: (VideoFrame, BroadcastVideoQualitySettings) -> VideoFrame,
    deliver: (VideoFrame) -> Unit,
) {
    val output = process(frame, settings)
    try {
        deliver(output)
    } finally {
        output.release()
    }
}
