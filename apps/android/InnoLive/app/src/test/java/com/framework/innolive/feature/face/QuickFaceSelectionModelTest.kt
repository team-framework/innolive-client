package com.framework.innolive.feature.face

import android.graphics.Bitmap
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickFaceSelectionModelTest {
    private val sources = FakeSources()
    private val scheduler = ManualScheduler()
    private val model = QuickFaceSelectionModel(sources, scheduler, firstFrameTimeoutMillis = 5_000)

    @Test
    fun startAsksForAFaceAsSoonAsFramesAreRequested() {
        model.start(mirrored = true)

        // iOS처럼 첫 감지 결과를 기다리지 않고 바로 얼굴을 비추라고 안내합니다.
        assertEquals(FaceSelectionPhase.Detecting(R.string.quick_face_show_face), model.phase)
        assertTrue(sources.opened.single().started)
        assertTrue(sources.opened.single().mirrored)
    }

    @Test
    fun newFrameReplacesAndReleasesThePreviousOne() {
        model.start(mirrored = false)
        val first = frame(selectable = true)
        val second = frame(selectable = false)

        sources.current.emit(first)
        scheduler.runPosted()
        assertEquals(FaceSelectionPhase.Detecting(R.string.quick_face_tap_to_register), model.phase)
        assertEquals(first.faces, model.faces)
        assertEquals(1080, model.frameWidth)

        sources.current.emit(second)
        scheduler.runPosted()
        assertTrue(first.isReleased)
        assertFalse(second.isReleased)
        assertEquals(FaceSelectionPhase.Detecting(R.string.quick_face_move_closer), model.phase)

        sources.current.emit(frame(faces = emptyList()))
        scheduler.runPosted()
        assertEquals(FaceSelectionPhase.Detecting(R.string.quick_face_show_face), model.phase)
    }

    @Test
    fun frameFromAnEarlierStartIsReleased() {
        model.start(mirrored = false)
        val earlier = sources.current
        model.start(mirrored = false)
        val stale = frame(selectable = true)

        earlier.emit(stale)
        scheduler.runPosted()

        assertTrue(earlier.stopped)
        assertTrue(stale.isReleased)
        assertTrue(model.faces.isEmpty())
        assertNull(model.takeFrameForSelection(0))
    }

    @Test
    fun selectedFrameIsHandedOverAndNotReleasedByTheModel() {
        model.start(mirrored = false)
        val frame = frame(selectable = true)
        sources.current.emit(frame)
        scheduler.runPosted()

        val taken = model.takeFrameForSelection(0)
        model.stop()
        model.close()

        assertSame(frame, taken)
        assertFalse(frame.isReleased)
        assertTrue(model.faces.isEmpty())
    }

    @Test
    fun faceThatCannotBeSelectedStaysWithTheModel() {
        model.start(mirrored = false)
        val frame = frame(selectable = false)
        sources.current.emit(frame)
        scheduler.runPosted()

        assertNull(model.takeFrameForSelection(0))
        assertNull(model.takeFrameForSelection(3))
        model.stop()

        assertTrue(frame.isReleased)
    }

    @Test
    fun missingFirstFrameFailsWithRetry() {
        model.start(mirrored = false)

        scheduler.runDelayed()

        assertEquals(FaceSelectionPhase.Failed(R.string.quick_face_camera_failed), model.phase)
        assertTrue(sources.current.stopped)
        model.start(mirrored = false)
        assertEquals(FaceSelectionPhase.Detecting(R.string.quick_face_show_face), model.phase)
        assertEquals(2, sources.opened.size)
    }

    @Test
    fun firstFrameCancelsTheTimeout() {
        model.start(mirrored = false)
        sources.current.emit(frame(selectable = true))
        scheduler.runPosted()

        scheduler.runDelayed()

        assertEquals(FaceSelectionPhase.Detecting(R.string.quick_face_tap_to_register), model.phase)
    }

    @Test
    fun cameraErrorFailsAndReleasesFrames() {
        model.start(mirrored = false)
        val shown = frame(selectable = true)
        sources.current.emit(shown)
        scheduler.runPosted()

        sources.current.fail()
        scheduler.runPosted()
        val late = frame(selectable = true)
        sources.current.emit(late)
        scheduler.runPosted()

        assertEquals(FaceSelectionPhase.Failed(R.string.quick_face_camera_failed), model.phase)
        assertTrue(sources.current.stopped)
        assertTrue(shown.isReleased)
        assertTrue(late.isReleased)
        assertNull(model.takeFrameForSelection(0))
    }

    @Test
    fun closeClosesSourcesAndIgnoresLaterStarts() {
        model.start(mirrored = false)
        model.close()
        model.start(mirrored = false)

        assertTrue(sources.closed)
        assertEquals(1, sources.opened.size)
        assertEquals(FaceSelectionPhase.Preparing, model.phase)
    }

    private fun frame(selectable: Boolean): FaceSelectionFrame =
        frame(listOf(FaceSelectionCandidate(FaceBox(0.3f, 0.3f, 0.6f, 0.5f), selectable)))

    private fun frame(faces: List<FaceSelectionCandidate>) = FaceSelectionFrame(FakeImage(), 1080, 1920, faces)

    private class FakeImage : FaceSelectionImage {
        override fun toUprightBitmap(): Bitmap = throw UnsupportedOperationException()

        override fun release() = Unit
    }

    private class FakeSource(
        val mirrored: Boolean,
        private val onFrame: (FaceSelectionFrame) -> Unit,
        private val onError: () -> Unit,
    ) : FaceSelectionSource {
        var started = false
        var stopped = false

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        fun emit(frame: FaceSelectionFrame) = onFrame(frame)

        fun fail() = onError()
    }

    private class FakeSources : FaceSelectionSources {
        val opened = mutableListOf<FakeSource>()
        var closed = false
        val current: FakeSource get() = opened.last()

        override fun open(
            mirrored: Boolean,
            onFrame: (FaceSelectionFrame) -> Unit,
            onError: () -> Unit,
        ): FaceSelectionSource = FakeSource(mirrored, onFrame, onError).also { opened += it }

        override fun close() {
            closed = true
        }
    }

    private class ManualScheduler : FaceSelectionScheduler {
        private val posted = ArrayDeque<() -> Unit>()
        private val delayed = mutableListOf<Pair<() -> Unit, BooleanArray>>()

        override fun post(action: () -> Unit) {
            posted.addLast(action)
        }

        override fun postDelayed(delayMillis: Long, action: () -> Unit): () -> Unit {
            val cancelled = BooleanArray(1)
            delayed += action to cancelled
            return { cancelled[0] = true }
        }

        fun runPosted() {
            while (posted.isNotEmpty()) posted.removeFirst().invoke()
        }

        fun runDelayed() {
            val due = delayed.toList()
            delayed.clear()
            due.filterNot { it.second[0] }.forEach { it.first() }
        }
    }
}
