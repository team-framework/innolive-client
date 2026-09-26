package com.framework.innolive.feature.live

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class VideoColorNativeFrameTest {
    @Test fun colorProcessingPreservesOddDimensionsLumaRotationTimestampAndSourceOwnership() {
        val released = AtomicInteger()
        val y = ByteBuffer.allocateDirect(24).apply { repeat(24) { put(it, (16 + it).toByte()) } }
        val u = ByteBuffer.allocateDirect(8).apply { repeat(8) { put(it, 128.toByte()) } }
        val v = ByteBuffer.allocateDirect(8).apply { repeat(8) { put(it, 128.toByte()) } }
        val source = JavaI420Buffer.wrap(5, 3, y, 8, u, 4, v, 4) { released.incrementAndGet() }
        val input = VideoFrame(source, 90, 123_456_789L)
        try {
            val result = VideoColorFrameProcessor().process(input, BroadcastVideoQualitySettings(warmth = 1f))
            try {
                assertEquals(90, result.rotation)
                assertEquals(123_456_789L, result.timestampNs)
                assertEquals(5, result.buffer.width)
                assertEquals(3, result.buffer.height)
                val output = requireNotNull(result.buffer.toI420())
                try {
                    repeat(3) { row -> repeat(5) { column ->
                        assertEquals(y.get(row * 8 + column), output.dataY.get(row * output.strideY + column))
                    } }
                    repeat(2) { row -> repeat(3) { column ->
                        assertEquals(110, output.dataU.get(row * output.strideU + column).toInt() and 0xff)
                        assertEquals(146, output.dataV.get(row * output.strideV + column).toInt() and 0xff)
                    } }
                } finally {
                    output.release()
                }
                assertEquals(128, u.get(0).toInt() and 0xff)
                assertEquals(128, v.get(0).toInt() and 0xff)
                assertEquals(0, released.get())
            } finally {
                result.release()
            }
            assertEquals(0, released.get())
        } finally {
            input.release()
        }
        assertEquals(1, released.get())
    }

    companion object {
        @JvmStatic @BeforeClass fun initializeWebRtc() {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                ).createInitializationOptions(),
            )
        }
    }
}
