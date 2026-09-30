package com.framework.innolive.feature.live

import android.graphics.Rect
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.privacy.PrivacyFaceCoordinator
import com.framework.innolive.feature.live.privacy.PrivacyFaceRecognitionService
import com.framework.innolive.feature.live.privacy.PrivacyFaceService
import com.framework.innolive.feature.live.privacy.PrivacyRegisteredFace
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
    @Test fun returningToProtectedModeWaitsForFacePreparationAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val secondPreparation = CountDownLatch(1)
        val preparationCount = AtomicInteger()
        val service = object : PrivacyFaceRecognitionService {
            override val ready = true
            override val canSubmit = false
            override fun prepare() = Unit
            override fun awaitPreparation(timeoutMillis: Long): Boolean {
                if (preparationCount.incrementAndGet() > 1) {
                    return secondPreparation.await(timeoutMillis, TimeUnit.MILLISECONDS)
                }
                return true
            }
            override fun takeResult(): PrivacyFaceService.Result? = null
            override fun submitRecognition(image: Bitmap, bounds: Rect, generation: Long,
                trackId: String, capturedAtSeconds: Double) = false
        }
        val analyzer = CameraFrameAnalyzer(object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) = Unit
        }, context, initialOnDevice = true).apply {
            faceCoordinatorFactory = {
                PrivacyFaceCoordinator(service) {
                    listOf(PrivacyRegisteredFace("fixture", "fixture",
                        FloatArray(512).apply { this[0] = 1f }, 0L))
                }
            }
        }
        analyzer.start()
        try {
            assertTrue(analyzer.awaitProtectedPreparation())
            analyzer.setProcessingMode(onDevice = false, anonymizationEnabled = true)
            analyzer.setProcessingMode(onDevice = true, anonymizationEnabled = true)
            assertFalse("Protected uplink resumed before face preparation count=${preparationCount.get()}",
                analyzer.awaitProtectedPreparation(0))
            secondPreparation.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!analyzer.awaitProtectedPreparation(0) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Protected uplink did not resume", analyzer.awaitProtectedPreparation(0))
        } finally {
            secondPreparation.countDown()
            analyzer.stop()
        }
    }

    @Test fun measure1080pUprightGpuProtectedDelivery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val delivered = AtomicReference<CountDownLatch>()
        val failed = AtomicInteger()
        val finishedNs = java.util.concurrent.atomic.AtomicLong()
        val outputGeometry = AtomicReference<Triple<Int, Int, Int>>()
        val outputTimestampNs = java.util.concurrent.atomic.AtomicLong()
        val outputIsTexture = AtomicReference<Boolean>()
        val analyzer = CameraFrameAnalyzer(object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) {
                outputGeometry.set(Triple(frame.buffer.width, frame.buffer.height, frame.rotation))
                outputTimestampNs.set(frame.timestampNs)
                outputIsTexture.set(frame.buffer is VideoFrame.TextureBuffer)
                finishedNs.set(System.nanoTime())
                delivered.get()?.countDown()
            }
        }, context, initialOnDevice = true, onProcessingFailure = {
            failed.incrementAndGet(); delivered.get()?.countDown()
        })
        analyzer.start()
        assertTrue(analyzer.awaitProtectedPreparation())
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
                assertEquals(Triple(1080, 1920, 0), outputGeometry.get())
                assertEquals(3_000_000_000L + sample * 100_000_000L, outputTimestampNs.get())
                assertTrue("Protected GPU output must remain a texture", outputIsTexture.get() == true)
                assertTrue(input.second.await(1, TimeUnit.SECONDS))
                Log.i("PrivacyPerformance", "camera_full sample=$sample copy_ms=$copyMs " +
                    "total_ms=${(finishedNs.get() - started) / 1e6}")
                // Delivery precedes clearing the worker's in-flight slot.
                Thread.sleep(10)
            }
        } finally { analyzer.stop() }
    }

    @Test fun rawFullFrameUsesPoolWithoutReusingHeldConsumerPixels() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val frames=java.util.concurrent.CopyOnWriteArrayList<VideoFrame>()
        val analyzer=CameraFrameAnalyzer(object:CapturerObserver {
            override fun onCapturerStarted(success:Boolean)=Unit
            override fun onCapturerStopped()=Unit
            override fun onFrameCaptured(frame:VideoFrame) {frame.retain();frames.add(frame)}
        },context)
        analyzer.start()
        try {
            for(value in listOf(35,180,90)) {
                val fixture=image(value.toLong()*1_000_000L,yValue=value)
                analyzer.analyze(fixture.first)
                assertTrue(fixture.second.await(1,TimeUnit.SECONDS))
            }
            assertEquals(3,frames.size)
            for((i,value) in listOf(35,180,90).withIndex()) {
                val buffer=checkNotNull(frames[i].buffer.toI420())
                try {assertEquals(value,buffer.dataY.get(0).toInt() and 255)} finally {buffer.release()}
            }
        } finally {frames.forEach {it.release()};analyzer.stop()}
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

    @Test fun protectedInferenceReleasesCameraInputAndDeliversWaitingFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val captured = AtomicInteger()
        val delivered = CountDownLatch(2)
        val timestamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val failed = CountDownLatch(1)
        val captureFormat = AtomicReference<Pair<Int, Int>>()
        val observer = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) {
                timestamps.add(frame.timestampNs)
                captured.incrementAndGet()
                delivered.countDown()
            }
        }
        val analyzer = CameraFrameAnalyzer(observer, context, initialOnDevice = true,
            onProcessingFailure = { failed.countDown() },
            onCaptureFormat = { width, height -> captureFormat.set(width to height) })
        analyzer.start()
        val warming=image(999_000_000L)
        analyzer.analyze(warming.first)
        assertTrue(warming.second.await(1,TimeUnit.SECONDS))
        assertTrue(analyzer.awaitProtectedPreparation())
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
            assertTrue("Protected frames were not delivered", delivered.await(20, TimeUnit.SECONDS))
            assertTrue(busy.second.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1_000_000_000L,1_010_000_000L),timestamps.toList())
            assertEquals(2, captured.get())
            assertFalse("Protected inference failed", failed.await(0, TimeUnit.MILLISECONDS))
        } finally { analyzer.stop() }
    }

    private fun image(timestampNs: Long, chromaPixelStride: Int = 1,
                      bufferOffset: Int = 0, width: Int = 640, height: Int = 480,
                      rotation: Int = 0, yValue:Int=114): Pair<ImageProxy, CountDownLatch> {
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
                repeat(width * height) { put(yValue.toByte()) }; flip()
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
