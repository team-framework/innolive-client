package com.framework.innolive.feature.live

import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.CapturerObserver
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CameraFrameAnalyzerDeviceTest {
    @Test fun protectedInferenceReleasesCameraInputBeforeItFinishesAndDropsBusyFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val captured = AtomicInteger()
        val delivered = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val captureFormat = AtomicReference<Pair<Int, Int>>()
        val observer = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) {
                assertEquals(1_000_000_000L, frame.timestampNs)
                captured.incrementAndGet()
                delivered.countDown()
            }
        }
        val analyzer = CameraFrameAnalyzer(observer, context, initialOnDevice = true,
            onProcessingFailure = { failed.countDown() },
            onCaptureFormat = { width, height -> captureFormat.set(width to height) })
        analyzer.start()
        try {
            val first = image(1_000_000_000L)
            val started = System.nanoTime()
            analyzer.analyze(first.first)
            val elapsedMs = (System.nanoTime() - started) / 1e6
            assertTrue("Camera analysis waited for model inference: $elapsedMs ms", elapsedMs < 500)
            assertTrue(first.second.await(1, TimeUnit.SECONDS))
            assertEquals(640 to 480, captureFormat.get())

            val busy = image(1_010_000_000L)
            analyzer.analyze(busy.first)
            assertTrue(busy.second.await(1, TimeUnit.SECONDS))
            assertTrue("Protected frame was not delivered", delivered.await(15, TimeUnit.SECONDS))
            Thread.sleep(500)
            assertEquals("A busy camera frame was queued", 1, captured.get())
            assertFalse("Protected inference failed", failed.await(0, TimeUnit.MILLISECONDS))
        } finally { analyzer.stop() }
    }

    private fun image(timestampNs: Long): Pair<ImageProxy, CountDownLatch> {
        val width = 640
        val height = 480
        val closed = CountDownLatch(1)
        fun plane(data: ByteBuffer, rowStride: Int): ImageProxy.PlaneProxy =
            Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader,
                arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
                when (method.name) {
                    "getBuffer" -> data
                    "getRowStride" -> rowStride
                    "getPixelStride" -> 1
                    else -> error("Unexpected plane method: ${method.name}")
                }
            } as ImageProxy.PlaneProxy
        val planes = arrayOf(
            plane(ByteBuffer.allocateDirect(width * height).apply {
                repeat(width * height) { put(114.toByte()) }; flip()
            }, width),
            plane(ByteBuffer.allocateDirect(width * height / 4).apply {
                repeat(width * height / 4) { put(128.toByte()) }; flip()
            }, width / 2),
            plane(ByteBuffer.allocateDirect(width * height / 4).apply {
                repeat(width * height / 4) { put(128.toByte()) }; flip()
            }, width / 2),
        )
        val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader,
            arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when (method.name) {
                "getRotationDegrees" -> 0
                "getTimestamp" -> timestampNs
                else -> error("Unexpected image info method: ${method.name}")
            }
        } as ImageInfo
        val proxy = Proxy.newProxyInstance(ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getCropRect" -> Rect(0, 0, width, height)
                "getPlanes" -> planes
                "getImageInfo" -> info
                "close" -> { closed.countDown(); Unit }
                else -> error("Unexpected image method: ${method.name}")
            }
        } as ImageProxy
        return proxy to closed
    }
}
