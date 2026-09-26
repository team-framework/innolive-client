package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyFaceAsyncDeviceTest {
    private val embedding = FloatArray(512).apply { this[0] = 1f }
    private val box = PrivacySegmentation.Box(100f, 100f, 150f, 150f)
    private val objects = listOf(PrivacySegmentation.Detection(box, .9f, 0, FloatArray(32)))
    private val layout = PrivacySegmentation.Letterbox(640, 640)

    private class Worker : PrivacyFaceRecognitionService, AutoCloseable {
        data class Job(val image: Bitmap, val bounds: Rect, val generation: Long, val id: String, val time: Double)
        var job: Job? = null
        var result: PrivacyFaceService.Result? = null
        var submissions = 0
        override val ready = true
        override val canSubmit get() = job == null && result == null
        override fun prepare() = Unit
        override fun takeResult(): PrivacyFaceService.Result? = result.also { result = null }
        override fun submitRecognition(image: Bitmap, bounds: Rect, generation: Long, trackId: String,
                                       capturedAtSeconds: Double): Boolean {
            if (!canSubmit) return false
            submissions++
            job = Job(image, Rect(bounds), generation, trackId, capturedAtSeconds)
            return true
        }
        fun complete(embedding: FloatArray?, imageBox: RectF = RectF(100f, 100f, 150f, 150f)) {
            val pending = checkNotNull(job)
            result = PrivacyFaceService.Result(pending.generation, pending.id, pending.time, embedding,
                sampleAvailable = true, imageBox = imageBox)
            pending.image.recycle(); job = null
        }
        override fun close() { job?.image?.recycle(); job = null; result = null }
    }

    private fun coordinator(worker: Worker) = PrivacyFaceCoordinator(worker) {
        listOf(PrivacyRegisteredFace("registered", "test", embedding, 0))
    }

    @Test fun stalledRecognitionDoesNotWaitOrGrowQueueAndOwnsCopiedPixels() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try {
                coordinator.beginFrame(image, 1_000_000_000L)
                coordinator.exceptions(image, objects, layout, 1_000_000_000L)
                coordinator.beginFrame(image, 1_350_000_000L)
                assertEquals(1, worker.submissions)
                image.eraseColor(Color.BLUE)
                assertEquals(Color.RED, checkNotNull(worker.job).image.getPixel(0, 0))
                val start = System.nanoTime()
                repeat(30) { i ->
                    val time = 1_350_000_000L + i * 10_000_000L
                    coordinator.beginFrame(image, time)
                    assertTrue(coordinator.exceptions(image, objects, layout, time).isEmpty())
                }
                assertTrue("Analyzer waited for a held recognition job", (System.nanoTime() - start) / 1e6 < 250)
                assertEquals(1, worker.submissions)
            } finally { image.recycle() }
        }
    }

    @Test fun lateResultWithinCacheExemptsLaterFramesAndExpiresAt500ms() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                coordinator.beginFrame(image, 1_000_000_000L)
                coordinator.exceptions(image, objects, layout, 1_000_000_000L)
                coordinator.beginFrame(image, 1_350_000_000L); worker.complete(embedding)
                assertTrue(coordinator.exceptions(image, objects, layout, 1_450_000_000L).isEmpty())
                coordinator.beginFrame(image, 1_700_000_000L); worker.complete(embedding)
                assertEquals(setOf(0), coordinator.exceptions(image, objects, layout, 1_800_000_000L))
                assertEquals(setOf(0), coordinator.exceptions(image, objects, layout, 2_199_000_000L))
                assertTrue(coordinator.exceptions(image, objects, layout, 2_200_000_000L).isEmpty())
            } finally { image.recycle() }
        }
    }

    @Test fun cachedExceptionSurvivesWaitingAndIsCancelledWhenUnknownResultArrives() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                coordinator.beginFrame(image, 1_000_000_000L)
                coordinator.exceptions(image, objects, layout, 1_000_000_000L)
                coordinator.beginFrame(image, 1_350_000_000L); worker.complete(embedding)
                assertTrue(coordinator.exceptions(image, objects, layout, 1_350_000_000L).isEmpty())
                coordinator.beginFrame(image, 1_700_000_000L); worker.complete(embedding)
                assertEquals(setOf(0), coordinator.exceptions(image, objects, layout, 1_700_000_000L))
                coordinator.beginFrame(image, 2_000_000_000L)
                assertEquals(setOf(0), coordinator.exceptions(image, objects, layout, 2_000_000_000L))
                worker.complete(null)
                assertTrue(coordinator.exceptions(image, objects, layout, 2_050_000_000L).isEmpty())
            } finally { image.recycle() }
        }
    }

    @Test fun expiredResultDoesNotCountTowardsNextConfirmation() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                coordinator.beginFrame(image, 1_000_000_000L)
                coordinator.exceptions(image, objects, layout, 1_000_000_000L)
                coordinator.beginFrame(image, 1_350_000_000L); worker.complete(embedding)
                coordinator.exceptions(image, objects, layout, 1_450_000_000L)
                coordinator.beginFrame(image, 1_700_000_000L)
                coordinator.exceptions(image, objects, layout, 1_800_000_000L)
                coordinator.exceptions(image, objects, layout, 2_000_000_000L)
                worker.complete(embedding)
                assertTrue(coordinator.exceptions(image, objects, layout, 2_200_000_000L).isEmpty())
                coordinator.beginFrame(image, 2_500_000_000L); worker.complete(embedding)
                assertTrue(coordinator.exceptions(image, objects, layout, 2_500_000_000L).isEmpty())
            } finally { image.recycle() }
        }
    }

    @Test fun recognizedFaceOutsideTrackedRegionCancelsCachedException() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                coordinator.beginFrame(image, 1_000_000_000L)
                coordinator.exceptions(image, objects, layout, 1_000_000_000L)
                coordinator.beginFrame(image, 1_350_000_000L); worker.complete(embedding)
                coordinator.exceptions(image, objects, layout, 1_350_000_000L)
                coordinator.beginFrame(image, 1_700_000_000L); worker.complete(embedding)
                assertEquals(setOf(0), coordinator.exceptions(image, objects, layout, 1_700_000_000L))
                coordinator.beginFrame(image, 2_000_000_000L)
                worker.complete(embedding, RectF(200f, 100f, 250f, 150f))
                assertTrue(coordinator.exceptions(image, objects, layout, 2_000_000_000L).isEmpty())
            } finally { image.recycle() }
        }
    }

    @Test fun resetAndGeometryChangeRejectInFlightResults() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            val changed = Bitmap.createBitmap(1280, 640, Bitmap.Config.ARGB_8888)
            try {
                coordinator.beginFrame(image, 1_000_000_000L)
                coordinator.exceptions(image, objects, layout, 1_000_000_000L)
                coordinator.beginFrame(image, 1_350_000_000L)
                coordinator.reset(); worker.complete(embedding)
                assertTrue(coordinator.exceptions(image, objects, layout, 1_350_000_000L).isEmpty())
                coordinator.beginFrame(image, 1_700_000_000L)
                coordinator.beginFrame(changed, 1_700_000_000L)
                worker.complete(embedding)
                assertTrue(coordinator.exceptions(changed, objects, PrivacySegmentation.Letterbox(1280, 640), 1_700_000_000L).isEmpty())
            } finally { image.recycle(); changed.recycle() }
        }
    }
}
