package com.framework.innolive.feature.live

import android.graphics.Rect
import android.util.Log
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
    @Test fun measure1080pCameraCopyThroughProtectedDelivery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val delivered = AtomicReference<CountDownLatch>()
        val failed = AtomicInteger()
        val finishedNs = java.util.concurrent.atomic.AtomicLong()
        val analyzer = CameraFrameAnalyzer(object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) {
                assertEquals(1920, frame.buffer.width)
                assertEquals(1080, frame.buffer.height)
                assertEquals(90, frame.rotation)
                finishedNs.set(System.nanoTime())
                delivered.get().countDown()
            }
        }, context, initialOnDevice = true, onProcessingFailure = {
            failed.incrementAndGet(); delivered.get().countDown()
        })
        analyzer.start()
        try {
            repeat(12) { sample ->
                val input = image(3_000_000_000L + sample * 100_000_000L,
                    chromaPixelStride = 2, bufferOffset = 5, width = 1920, height = 1080, rotation = 90)
                val latch = CountDownLatch(1)
                delivered.set(latch)
                val started = System.nanoTime()
                analyzer.analyze(input.first)
                val copyMs = (System.nanoTime() - started) / 1e6
                assertTrue(latch.await(20, TimeUnit.SECONDS))
                assertEquals(0, failed.get())
                assertTrue(input.second.await(1, TimeUnit.SECONDS))
                Log.i("PrivacyPerformance", "camera_full sample=$sample copy_ms=$copyMs " +
                    "total_ms=${(finishedNs.get() - started) / 1e6}")
                // Delivery precedes clearing the worker's in-flight slot.
                Thread.sleep(10)
            }
        } finally { analyzer.stop() }
    }

    @Test fun rawCameraFrameCopiesInterleavedChromaWithBufferOffset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val captured = AtomicInteger()
        val failure = AtomicInteger()
        val analyzer = CameraFrameAnalyzer(object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) {
                val buffer = checkNotNull(frame.buffer.toI420())
                try {
                    assertEquals(640, buffer.width)
                    assertEquals(480, buffer.height)
                    for (y in 0 until 240) for (x in 0 until 320) {
                        assertEquals(128.toByte(), buffer.dataU.get(y * buffer.strideU + x))
                        assertEquals(128.toByte(), buffer.dataV.get(y * buffer.strideV + x))
                    }
                    captured.incrementAndGet()
                } finally { buffer.release() }
            }
        }, context, onProcessingFailure = { failure.incrementAndGet() })
        analyzer.start()
        try {
            val input = image(2_000_000_000L, chromaPixelStride = 2, bufferOffset = 5)
            analyzer.analyze(input.first)
            assertTrue(input.second.await(1, TimeUnit.SECONDS))
            assertEquals(1, captured.get())
            assertEquals(0, failure.get())
        } finally { analyzer.stop() }
    }

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

    private fun image(timestampNs: Long, chromaPixelStride: Int = 1,
                      bufferOffset: Int = 0, width: Int = 640, height: Int = 480,
                      rotation: Int = 0): Pair<ImageProxy, CountDownLatch> {
        val closed = CountDownLatch(1)
        fun plane(data: ByteBuffer, rowStride: Int, pixelStride: Int = 1): ImageProxy.PlaneProxy =
            Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader,
                arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
                when (method.name) {
                    "getBuffer" -> data
                    "getRowStride" -> rowStride
                    "getPixelStride" -> pixelStride
                    else -> error("Unexpected plane method: ${method.name}")
                }
            } as ImageProxy.PlaneProxy
        val planes = arrayOf(
            plane(ByteBuffer.allocateDirect(width * height).apply {
                repeat(width * height) { put(114.toByte()) }; flip()
            }, width),
            plane(ByteBuffer.allocateDirect(bufferOffset + width * height / 4 * chromaPixelStride).apply {
                repeat(capacity()) { put(128.toByte()) }; flip(); position(bufferOffset)
            }, width / 2 * chromaPixelStride, chromaPixelStride),
            plane(ByteBuffer.allocateDirect(bufferOffset + width * height / 4 * chromaPixelStride).apply {
                repeat(capacity()) { put(128.toByte()) }; flip(); position(bufferOffset)
            }, width / 2 * chromaPixelStride, chromaPixelStride),
        )
        val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader,
            arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when (method.name) {
                "getRotationDegrees" -> rotation
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
