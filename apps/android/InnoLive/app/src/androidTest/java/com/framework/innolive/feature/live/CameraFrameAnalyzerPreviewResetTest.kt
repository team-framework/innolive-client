package com.framework.innolive.feature.live

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CameraFrameAnalyzerPreviewResetTest {
    @Test
    fun cameraRebindWaitsForFrameAcceptanceAndRejectsOldExposure() {
        assertResetWaitsForFrame { resetLookPreviewSample() }
    }

    @Test
    fun exposureChangeWaitsForFrameAcceptanceBeforeInvalidatingItsTicket() {
        assertResetWaitsForFrame { beginPreviewExposure() }
    }

    @Test
    fun cameraRebindCannotFreezeAnOldCameraImageAsTheNewPreset() {
        CameraFrameAnalyzer().use { analyzer ->
            analyzer.setVideoQualitySettings(VideoLookPreset.VIVID.applyTo(BroadcastVideoQualitySettings()))
            analyzer.setLookPreviewEnabled(true)
            analyzer.recordPreviewExposure(100, 0f, true)
            analyzer.resetLookPreviewSample()

            val previousCamera = YuvImage(frameTimestamp = 150, luma = 40)
            analyzer.analyze(previousCamera)
            assertTrue(previousCamera.closed.get())
            assertEquals("Rebind must reject the old camera before copying its pixels", 0, previousCamera.planeReads.get())

            analyzer.recordPreviewExposure(200, 0f, true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (analyzer.lookPreviews.value == null && System.nanoTime() < deadline) {
                analyzer.analyze(YuvImage(frameTimestamp = 200, luma = 235))
                Thread.sleep(150)
            }
            val newPreview = checkNotNull(analyzer.lookPreviews.value) { "New camera preview was not published" }
            val pixel = newPreview.previews.getValue(VideoLookPreset.VIVID).getPixel(0, 0)
            assertTrue("New camera must replace the old dark sample", android.graphics.Color.red(pixel) > 200)
        }
    }

    private fun assertResetWaitsForFrame(reset: CameraFrameAnalyzer.() -> Unit) {
        val executor = Executors.newFixedThreadPool(2)
        val reachedExposureCheck = CountDownLatch(1)
        val resumeFrame = CountDownLatch(1)
        val resetThread = AtomicReference<Thread>()
        CameraFrameAnalyzer().use { analyzer ->
            analyzer.setLookPreviewEnabled(true)
            analyzer.recordPreviewExposure(100, 0f, true)
            val image = YuvImage(frameTimestamp = 150, luma = 40) {
                reachedExposureCheck.countDown()
                check(resumeFrame.await(5, TimeUnit.SECONDS)) { "Frame was not released" }
            }
            val analyzing = executor.submit { analyzer.analyze(image) }
            var resetting: java.util.concurrent.Future<*>? = null
            try {
                assertTrue(reachedExposureCheck.await(5, TimeUnit.SECONDS))
                resetting = executor.submit {
                    resetThread.set(Thread.currentThread())
                    analyzer.reset()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!resetting.isDone && resetThread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                assertFalse("Reset must not complete while frame acceptance holds the capture lock", resetting.isDone)
                assertEquals(Thread.State.BLOCKED, resetThread.get()?.state)
            } finally {
                resumeFrame.countDown()
                analyzing.get(5, TimeUnit.SECONDS)
                resetting?.get(5, TimeUnit.SECONDS)
                executor.shutdownNow()
            }
            assertTrue(image.closed.get())
            val oldFrame = YuvImage(frameTimestamp = 160, luma = 40)
            analyzer.analyze(oldFrame)
            assertEquals("Reset exposure must reject old frames", 0, oldFrame.planeReads.get())
            assertTrue(oldFrame.closed.get())
        }
    }

    private class YuvImage(
        private val frameTimestamp: Long,
        private val luma: Int,
        private val beforeTimestamp: () -> Unit = {},
    ) : ImageProxy {
        val closed = AtomicBoolean(false)
        val planeReads = AtomicInteger()
        private var crop = Rect(0, 0, 4, 4)
        private val info = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp(): Long {
                beforeTimestamp()
                return frameTimestamp
            }
            override fun getRotationDegrees(): Int = 0
            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }

        override fun close() { closed.set(true) }
        override fun getCropRect(): Rect = crop
        override fun setCropRect(rect: Rect?) { crop = rect ?: Rect(0, 0, 4, 4) }
        override fun getFormat(): Int = ImageFormat.YUV_420_888
        override fun getHeight(): Int = 4
        override fun getWidth(): Int = 4
        override fun getImageInfo(): ImageInfo = info
        override fun getImage(): Image? = null
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
            planeReads.incrementAndGet()
            return arrayOf(plane(4, luma), plane(2, 128), plane(2, 128))
        }
        private fun plane(size: Int, value: Int) = object : ImageProxy.PlaneProxy {
            override fun getRowStride(): Int = size
            override fun getPixelStride(): Int = 1
            override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(ByteArray(size * size) { value.toByte() })
        }
    }
}
