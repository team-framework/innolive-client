package com.framework.innolive.feature.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableFaceTrackerTest {
    private val centeredFace = FaceObservation(
        centerX = 0.5f,
        centerY = 0.5f,
        widthFraction = 0.35f,
        heightFraction = 0.35f,
        trackingId = 7,
    )

    @Test
    fun oneFaceMustRemainStableForThreeFramesAnd600Milliseconds() {
        val tracker = StableFaceTracker()

        assertEquals(FaceStabilityStatus.WAITING, tracker.update(listOf(centeredFace), 0))
        assertEquals(FaceStabilityStatus.WAITING, tracker.update(listOf(centeredFace), 300))
        assertEquals(FaceStabilityStatus.STABLE, tracker.update(listOf(centeredFace), 600))
        assertEquals(FaceStabilityStatus.STABLE, tracker.update(listOf(centeredFace), 800))
    }

    @Test
    fun zeroOrMultipleFacesResetTheStableSequence() {
        val tracker = StableFaceTracker()
        val secondFace = centeredFace.copy(centerX = 0.6f, trackingId = 8)

        tracker.update(listOf(centeredFace), 0)
        assertEquals(FaceStabilityStatus.NO_FACE, tracker.update(emptyList(), 100))
        assertEquals(FaceStabilityStatus.WAITING, tracker.update(listOf(centeredFace), 200))
        assertEquals(
            FaceStabilityStatus.MULTIPLE_FACES,
            tracker.update(listOf(centeredFace, secondFace), 300),
        )
        assertEquals(FaceStabilityStatus.WAITING, tracker.update(listOf(centeredFace), 400))
    }

    @Test
    fun anchorDriftRestartsTheSequence() {
        val tracker = StableFaceTracker()
        val driftedFace = centeredFace.copy(centerX = 0.59f)

        tracker.update(listOf(centeredFace), 0)
        assertEquals(FaceStabilityStatus.MOVING, tracker.update(listOf(driftedFace), 300))
        assertEquals(FaceStabilityStatus.WAITING, tracker.update(listOf(driftedFace), 400))
        assertNotEquals(FaceStabilityStatus.STABLE, tracker.update(listOf(driftedFace), 800))
    }

    @Test
    fun trackingIdChangeRestartsTheSequence() {
        val tracker = StableFaceTracker()
        val changedTrackingId = centeredFace.copy(trackingId = 9)

        tracker.update(listOf(centeredFace), 0)
        assertEquals(
            FaceStabilityStatus.WAITING,
            tracker.update(listOf(changedTrackingId), 300),
        )
        assertEquals(
            FaceStabilityStatus.WAITING,
            tracker.update(listOf(changedTrackingId), 600),
        )
    }

    @Test
    fun frameGapOver600MillisecondsResetsTheSequence() {
        val tracker = StableFaceTracker()

        tracker.update(listOf(centeredFace), 0)
        assertEquals(FaceStabilityStatus.WAITING, tracker.update(listOf(centeredFace), 601))
        assertEquals(FaceStabilityStatus.WAITING, tracker.update(listOf(centeredFace), 901))
        assertEquals(FaceStabilityStatus.STABLE, tracker.update(listOf(centeredFace), 1_501))
    }
}
