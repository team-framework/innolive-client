package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

class VideoColorFrameProcessorTest {
    @Test fun neutralColorRetainsTheOriginalFrameAndMetadata() {
        val buffer = FakeFrameBuffer()
        val frame = VideoFrame(buffer, 270, 123_456_789L)
        val output = VideoColorFrameProcessor().process(frame, BroadcastVideoQualitySettings(exposureEV = 1f))
        assertSame(frame, output)
        assertEquals(2, buffer.references)
        assertEquals(270, output.rotation)
        assertEquals(123_456_789L, output.timestampNs)
        output.release()
        assertEquals(1, buffer.references)
        frame.release()
        assertEquals(0, buffer.references)
    }

    @Test fun zeroSaturationRemovesChromaAfterWarmth() {
        for (warmth in listOf(-1f, 0f, 1f)) {
            val transform = VideoColorTransform(warmth, 0f)
            assertTrue(transform.u.all { it == 128 })
            assertTrue(transform.v.all { it == 128 })
        }
    }

    @Test fun warmthMovesBlueAndRedInOppositeDirectionsAndClamps() {
        val warm = VideoColorTransform(1f, 1f)
        val cool = VideoColorTransform(-1f, 1f)
        assertTrue(warm.u[128] < 128 && warm.v[128] > 128)
        assertTrue(cool.u[128] > 128 && cool.v[128] < 128)
        val vivid = VideoColorTransform(1f, 2f)
        assertEquals(0, vivid.u.first())
        assertEquals(255, vivid.v.last())
        assertTrue((vivid.u + vivid.v).all { it in 0..255 })
    }

    @Test fun chromaProcessingPreservesBufferPositionsSourceBytesAndRowPadding() {
        val sourceBytes = byteArrayOf(9, 100, 110, 88, 90, 120, 130.toByte(), 77)
        val source = ByteBuffer.wrap(sourceBytes.copyOf()).apply { position(1); limit(7) }
        val target = ByteBuffer.wrap(ByteArray(10) { 42 }).apply { position(2) }
        val transform = VideoColorTransform(0f, 2f)
        transform.applyChroma(source, 4, target, 5, 2, 2, transform.u)
        assertEquals(1, source.position())
        assertEquals(7, source.limit())
        assertEquals(2, target.position())
        assertArrayEquals(sourceBytes, source.array())
        assertEquals(72, target.get(2).toInt() and 0xff)
        assertEquals(92, target.get(3).toInt() and 0xff)
        assertEquals(112, target.get(7).toInt() and 0xff)
        assertEquals(132, target.get(8).toInt() and 0xff)
        for (index in listOf(0, 1, 4, 5, 6, 9)) assertEquals(42, target.get(index).toInt())
    }

    @Test fun lumaCopyRespectsDifferentStridesAndLeavesCameraBuffersUntouched() {
        val source = ByteBuffer.wrap(byteArrayOf(9, 16, 17, 18, 99, 19, 20, 21)).apply { position(1) }
        val target = ByteBuffer.wrap(ByteArray(12) { 42 }).apply { position(2) }
        copyVideoPlane(source, 4, target, 5, 3, 2)
        assertEquals(1, source.position())
        assertEquals(2, target.position())
        assertArrayEquals(byteArrayOf(42, 42, 16, 17, 18, 42, 42, 19, 20, 21, 42, 42), target.array())
    }

    @Test fun adjustmentFailureNeverDeliversRawInput() {
        val buffer = FakeFrameBuffer()
        val frame = VideoFrame(buffer, 90, 99L)
        var delivered = 0
        assertThrows(IllegalStateException::class.java) {
            relayColorFrame(frame, BroadcastVideoQualitySettings(warmth = 1f), { _, _ ->
                error("processing failed")
            }, { delivered++ })
        }
        assertEquals(0, delivered)
        assertEquals(1, buffer.references)
        frame.release()
    }

    @Test fun relayReleasesProcessedReferenceEvenWhenTheObserverThrows() {
        val inputBuffer = FakeFrameBuffer()
        val outputBuffer = FakeFrameBuffer()
        val input = VideoFrame(inputBuffer, 0, 10L)
        val output = VideoFrame(outputBuffer, 0, 10L)
        assertThrows(IllegalStateException::class.java) {
            relayColorFrame(input, BroadcastVideoQualitySettings(), { _, _ -> output }, {
                assertSame(output, it)
                error("observer failed")
            })
        }
        assertEquals(0, outputBuffer.references)
        assertEquals(1, inputBuffer.references)
        input.release()
    }

    private class FakeFrameBuffer : VideoFrame.Buffer {
        var references = 1
        override fun getWidth() = 5
        override fun getHeight() = 3
        override fun retain() { references++ }
        override fun release() { references-- }
        override fun toI420(): VideoFrame.I420Buffer = error("Neutral adjustment must not convert")
        override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): VideoFrame.Buffer =
            error("Neutral adjustment must not scale")
    }
}
