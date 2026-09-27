package com.framework.innolive.feature.live.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.EncodedImage
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the same shared EGL surface encoder used for broadcasting, without signaling/network. */
@RunWith(AndroidJUnit4::class)
class PrivacyGpuEncoderDeviceTest {
    @Test fun protectedTextureEncodesOnHardwareSurfaceWithoutCpuI420Output() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl = EglBase.create()
        val factory = HardwareVideoEncoderFactory(egl.eglBaseContext, true, true)
        val codec = checkNotNull(factory.supportedCodecs.firstOrNull { it.name == "H264" })
        val encoder = checkNotNull(factory.createEncoder(codec))
        assertTrue(encoder.isHardwareEncoder)
        val encoded = CountDownLatch(3)
        val errors = AtomicInteger()
        val source = JavaI420Buffer.allocate(320, 320)
        repeat(source.dataY.capacity()) { source.dataY.put(it, (if (it % 2 == 0) 16 else 235).toByte()) }
        repeat(source.dataU.capacity()) { source.dataU.put(it, 128.toByte()) }
        repeat(source.dataV.capacity()) { source.dataV.put(it, 128.toByte()) }
        try {
            assertEquals(VideoCodecStatus.OK, encoder.initEncode(
                VideoEncoder.Settings(4, 320, 320, 500, 30, 1, false, VideoEncoder.Capabilities(false))) { image, _ ->
                if (image.encodedWidth != 320 || image.encodedHeight != 320 || image.buffer.remaining() == 0)
                    errors.incrementAndGet()
                encoded.countDown()
            })
            PrivacyGpuFramePipeline(egl.eglBaseContext, useGles3=true, useFence=true).use { graph ->
                val mask = ByteArray(160 * 160) { -1 }
                repeat(12) { index ->
                    val layout = graph.prepare(source, 90)
                    val texture = graph.finish(mask, layout, 320, 320)
                    val frame = VideoFrame(texture, 90, System.nanoTime())
                    try {
                        assertEquals(VideoCodecStatus.OK, encoder.encode(frame, VideoEncoder.EncodeInfo(arrayOf(
                            if (index == 0) EncodedImage.FrameType.VideoFrameKey else EncodedImage.FrameType.VideoFrameDelta))))
                    } finally { frame.release() }
                    Thread.sleep(40)
                }
                assertTrue("No hardware encoded frames", encoded.await(5, TimeUnit.SECONDS))
                assertEquals(0, errors.get())
                assertEquals(VideoCodecStatus.OK, encoder.release())
            }
        } finally { runCatching { encoder.release() }; source.release(); egl.release() }
    }
}
