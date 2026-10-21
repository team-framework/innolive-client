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
    private val objects = listOf(PrivacySegmentation.Detection(box, .9f, 0, FloatArray(32), trackId = 1))
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
        fun complete(embedding: FloatArray?, sampleAvailable: Boolean = true,
                     imageBox: RectF = RectF(100f, 100f, 150f, 150f)) {
            val pending = checkNotNull(job)
            result = PrivacyFaceService.Result(pending.generation, pending.id, pending.time, embedding,
                sampleAvailable = sampleAvailable, imageBox = imageBox)
            pending.image.recycle(); job = null
        }
        override fun close() { job?.image?.recycle(); job = null; result = null }
    }

    private fun coordinator(worker: Worker) = PrivacyFaceCoordinator(worker) {
        listOf(PrivacyRegisteredFace("registered", "test", embedding, 0))
    }

    @Test fun cachedLibraryIsReadOnceAndDeletionRevisionImmediatelyRevokesExceptions() {
        Worker().use { worker ->
            var revision=0L
            var reads=0
            var entries=listOf(PrivacyRegisteredFace("registered","test",embedding,0))
            val coordinator=PrivacyFaceCoordinator(worker,revisionSource={revision}) {reads++;entries}
            val image=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888)
            try {
                val confirmed=confirm(coordinator,worker,image)
                assertEquals(1,reads)
                entries=emptyList();revision++
                assertTrue(frame(coordinator,image,confirmed+10).isEmpty())
                assertEquals(2,reads)
                repeat(20) {assertTrue(frame(coordinator,image,confirmed+20+it).isEmpty())}
                assertEquals(2,reads)
            } finally {image.recycle()}
        }
    }

    @Test fun stalledRecognitionDoesNotWaitOrGrowQueueAndOwnsCopiedPixels() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try {
                coordinator.beginFrame(image, 1_000_000_000L)
                coordinator.exceptions(image, objects, layout, 1_000_000_000L)
                coordinator.beginFrame(image, 1_100_000_000L)
                assertEquals(1, worker.submissions)
                image.eraseColor(Color.BLUE)
                assertEquals(Color.RED, checkNotNull(worker.job).image.getPixel(0, 0))
                val start = System.nanoTime()
                repeat(30) { i ->
                    val time = 1_100_000_000L + i * 10_000_000L
                    coordinator.beginFrame(image, time)
                    assertTrue(coordinator.exceptions(image, objects, layout, time).isEmpty())
                }
                assertTrue("Analyzer waited for a held recognition job", (System.nanoTime() - start) / 1e6 < 250)
                assertEquals(1, worker.submissions)
            } finally { image.recycle() }
        }
    }

    private fun frame(coordinator: PrivacyFaceCoordinator, image: Bitmap, millis: Long): Set<Int> {
        coordinator.beginFrame(image, millis * 1_000_000)
        return coordinator.exceptions(image, objects, layout, millis * 1_000_000)
    }

    /** Runs 100ms frames until the next recognition is submitted; returns that frame's time. */
    private fun untilSubmitted(coordinator: PrivacyFaceCoordinator, worker: Worker, image: Bitmap, from: Long,
                               expected: Set<Int>): Long {
        var time = from
        while (worker.job == null) {
            assertEquals("frame=$time", expected, frame(coordinator, image, time))
            check(time - from < 5_000) { "No recognition was scheduled" }
            time += 100
        }
        return time - 100
    }

    /** One matching result approves the track on the next frame. Returns that frame. */
    private fun confirm(coordinator: PrivacyFaceCoordinator, worker: Worker, image: Bitmap): Long {
        val first = untilSubmitted(coordinator, worker, image, 1000, emptySet())
        worker.complete(embedding)
        // The result is used on the next frame without blocking capture.
        assertEquals(setOf(0), frame(coordinator, image, first + 100))
        return first + 100
    }

    @Test fun approvedTrackIsNeverRecheckedAndStaysExempt() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                val confirmed = confirm(coordinator, worker, image)
                for (time in confirmed + 100..confirmed + 6_000 step 100)
                    assertEquals(setOf(0), frame(coordinator, image, time))
                assertEquals("no revalidation after approval", 1, worker.submissions)
            } finally { image.recycle() }
        }
    }

    @Test fun addingAnotherRegisteredFaceKeepsTheApproval() {
        Worker().use { worker ->
            var revision = 0L
            var entries = listOf(PrivacyRegisteredFace("registered", "test", embedding, 0))
            val coordinator = PrivacyFaceCoordinator(worker, revisionSource = { revision }) { entries }
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                val confirmed = confirm(coordinator, worker, image)
                entries = entries + PrivacyRegisteredFace("added", "other", FloatArray(512).apply { this[1] = 1f }, 0)
                revision++
                assertEquals(setOf(0), frame(coordinator, image, confirmed + 100))
            } finally { image.recycle() }
        }
    }

    @Test fun unmatchedFaceIsRetriedQuicklyAndStaysProtected() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                val first = untilSubmitted(coordinator, worker, image, 1000, emptySet())
                worker.complete(null)
                val retry = untilSubmitted(coordinator, worker, image, first + 100, emptySet())
                assertEquals("quick retry five frames later", first + 500, retry)
            } finally { image.recycle() }
        }
    }

    @Test fun missingLandmarksDoNotApprove() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                val first = untilSubmitted(coordinator, worker, image, 1000, emptySet())
                worker.complete(embedding, sampleAvailable = false)
                assertTrue(frame(coordinator, image, first + 100).isEmpty())
            } finally { image.recycle() }
        }
    }

    @Test fun recognitionFromAnotherLocationDoesNotApprove() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                val first = untilSubmitted(coordinator, worker, image, 1000, emptySet())
                worker.complete(embedding, imageBox = RectF(200f, 100f, 250f, 150f))
                assertTrue(frame(coordinator, image, first + 100).isEmpty())
            } finally { image.recycle() }
        }
    }

    @Test fun oneSecondFrameGapDiscardsInFlightRecognition() {
        Worker().use { worker ->
            val coordinator = coordinator(worker)
            val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                val first = untilSubmitted(coordinator, worker, image, 1000, emptySet())
                worker.complete(embedding)
                assertTrue("the result from before the gap is ignored",
                    coordinator.exceptions(image, objects, layout, (first + 1_200) * 1_000_000).isEmpty())
                val second = untilSubmitted(coordinator, worker, image, first + 1_300, emptySet())
                worker.complete(embedding)
                assertEquals(setOf(0), frame(coordinator, image, second + 100))
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
                coordinator.beginFrame(image, 1_100_000_000L)
                coordinator.reset(); worker.complete(embedding)
                assertTrue(coordinator.exceptions(image, objects, layout, 1_100_000_000L).isEmpty())
                coordinator.beginFrame(image, 1_700_000_000L)
                coordinator.beginFrame(changed, 1_700_000_000L)
                worker.complete(embedding)
                assertTrue(coordinator.exceptions(changed, objects, PrivacySegmentation.Letterbox(1280, 640), 1_700_000_000L).isEmpty())
            } finally { image.recycle(); changed.recycle() }
        }
    }
}
