package com.framework.innolive.feature.live

import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Applies color before the WebRTC source so the sender and local track share the same pixels. */
internal class VideoColorFrameProcessor : AutoCloseable {
    private var cachedTransform = VideoColorTransform(0f, 1f)
    // The encoder and local renderers release frames after the next capture may arrive.
    private val outputs = PrivacyCameraBufferPool(maxIdle = 3)

    /** The caller owns the returned reference, including the unchanged-frame fast path. */
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
            val output = outputs.acquire(source.width, source.height)
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

    override fun close() = outputs.close()
}

/**
 * Chroma lookup tables avoid RGB conversion and floating-point work at capture resolution.
 * Warmth moves blue/red chroma in opposite directions; saturation scales both around neutral.
 * Luminance stays camera-controlled, including the hardware exposure compensation.
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

    /** Maps whole rows through a heap array; per-sample ByteBuffer access dominated a 1080p frame. */
    fun applyChroma(
        source: ByteBuffer,
        sourceStride: Int,
        target: ByteBuffer,
        targetStride: Int,
        width: Int,
        height: Int,
        table: IntArray,
    ) {
        val lookup = ByteArray(256) { table[it].toByte() }
        val scratch = ByteArray(width)
        val input = source.duplicate()
        val output = target.duplicate()
        val sourceStart = input.position()
        val targetStart = output.position()
        repeat(height) { index ->
            input.limit(source.limit())
            input.position(sourceStart + index * sourceStride)
            input.get(scratch, 0, width)
            for (column in 0 until width) scratch[column] = lookup[scratch[column].toInt() and 0xff]
            output.position(targetStart + index * targetStride)
            output.put(scratch, 0, width)
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

/** A failed adjustment deliberately drops the frame; it never publishes the unadjusted input. */
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
