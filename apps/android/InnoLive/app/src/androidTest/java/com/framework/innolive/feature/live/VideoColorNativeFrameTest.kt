package com.framework.innolive.feature.live

import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Color
import android.opengl.GLES20
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrameDrawer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class VideoColorNativeFrameTest {
    @Test fun presetColorsMatchActualWebRtcGpuOutputForTheSameSource() {
        val egl = EglBase.create(null, EglBase.CONFIG_RGBA)
        egl.createPbufferSurface(8, 8)
        egl.makeCurrent()
        val drawer = GlRectDrawer()
        val frameDrawer = VideoFrameDrawer()
        try {
            VideoLookPreviewRenderer().use { renderer ->
                for (preset in VideoLookPreset.entries) {
                    for ((y, u, v) in listOf(
                        Triple(16, 128, 128), Triple(235, 128, 128), Triple(128, 128, 128),
                        Triple(70, 16, 240), Triple(220, 240, 16), Triple(100, 90, 170),
                    )) {
                        val snapshot = PreviewYuvSnapshot(8, 8,
                            ByteArray(64) { y.toByte() }, ByteArray(16) { u.toByte() }, ByteArray(16) { v.toByte() })
                        val settings = preset.applyTo(BroadcastVideoQualitySettings())
                        // Equal EV means the source already has the selected hardware exposure.
                        val card = renderer.render(snapshot, settings, preset.exposureEV).previews.getValue(preset)
                        val source = JavaI420Buffer.allocate(8, 8)
                        repeat(64) { source.dataY.put(it, y.toByte()) }
                        repeat(16) { source.dataU.put(it, u.toByte()); source.dataV.put(it, v.toByte()) }
                        val input = VideoFrame(source, 0, 1L)
                        val actual = VideoColorFrameProcessor().process(input, settings)
                        try {
                            frameDrawer.drawFrame(actual, drawer, null, 0, 0, 8, 8)
                            val pixels = ByteBuffer.allocateDirect(8 * 8 * 4)
                            GLES20.glReadPixels(0, 0, 8, 8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                            val expected = card.getPixel(4, 4)
                            for ((channel, value) in listOf(Color.red(expected), Color.green(expected), Color.blue(expected)).withIndex()) {
                                val gpu = pixels.get((4 * 8 + 4) * 4 + channel).toInt() and 0xff
                                assertTrue("$preset YUV=($y,$u,$v) channel=$channel bitmap=$value GPU=$gpu",
                                    kotlin.math.abs(value - gpu) <= 2)
                            }
                        } finally {
                            actual.release()
                            input.release()
                        }
                    }
                }
            }
        } finally {
            frameDrawer.release()
            drawer.release()
            egl.release()
        }
    }

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
