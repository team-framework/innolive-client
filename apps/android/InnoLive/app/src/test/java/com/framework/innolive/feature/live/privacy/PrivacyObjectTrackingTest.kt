package com.framework.innolive.feature.live.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyObjectTrackingTest {
    private val coefficients = FloatArray(32) { it * .01f }
    private fun face(left: Float, score: Float = .9f, top: Float = 100f) =
        PrivacySegmentation.Detection(PrivacySegmentation.Box(left, top, left + 50f, top + 50f), score, 0, coefficients)

    @Test fun lowScoreCandidateOnlyContinuesAnExistingTrack() {
        val tracking = PrivacyObjectTracking()
        val first = tracking.update(listOf(face(100f)), 1.0)
        val id = first.single().trackId
        assertNotEquals(PrivacySegmentation.NO_TRACK, id)
        val continued = tracking.update(listOf(face(102f, .15f), face(400f, .15f)), 1.033)
        assertEquals("the weak face elsewhere stays unprotected", 1, continued.size)
        assertEquals(id, continued.single().trackId)
        assertEquals(.15f, continued.single().score)
        assertFalse(continued.single().held)
    }

    @Test fun unconfirmedHighScoreDetectionIsStillProtected() {
        val tracking = PrivacyObjectTracking()
        tracking.update(listOf(face(100f)), 1.0)
        val objects = tracking.update(listOf(face(100f), face(400f, .4f)), 1.033)
        assertEquals(2, objects.size)
        assertEquals(PrivacySegmentation.NO_TRACK, objects[1].trackId)
        val confirmed = tracking.update(listOf(face(100f), face(400f, .4f)), 1.067)
        assertNotEquals(PrivacySegmentation.NO_TRACK, confirmed[1].trackId)
    }

    @Test fun lostTrackKeepsItsMaskForExactlyOneFrameAtThePredictedBox() {
        val tracking = PrivacyObjectTracking()
        tracking.update(listOf(face(100f)), 1.0)
        val id = tracking.update(listOf(face(104f)), 1.033).single().trackId
        val held = tracking.update(emptyList(), 1.067).single()
        assertTrue(held.held)
        assertEquals(id, held.trackId)
        assertEquals(.9f * PrivacyObjectTracking.HOLD_CONFIDENCE_DECAY, held.score, 1e-6f)
        assertSame(coefficients, held.coefficients)
        assertTrue("the box moves with the track's velocity", held.box.left > 104f)
        assertTrue(tracking.update(emptyList(), 1.1).isEmpty())
    }

    @Test fun heldTrackIsSkippedWhereACurrentObjectAlreadyCoversIt() {
        val tracking = PrivacyObjectTracking()
        tracking.update(listOf(face(100f)), 1.0)
        tracking.update(listOf(face(100f)), 1.033)
        // IoU 0.6 with a .3 score is too weak to continue the track, but it protects the same place.
        val objects = tracking.update(listOf(face(112.5f, .3f)), 1.067)
        assertEquals(1, objects.size)
        assertFalse(objects.single().held)
        assertEquals(PrivacySegmentation.NO_TRACK, objects.single().trackId)
    }

    @Test fun frameGapStartsNewTracksWithoutHolding() {
        val tracking = PrivacyObjectTracking()
        val id = tracking.update(listOf(face(100f)), 1.0).single().trackId
        assertTrue(tracking.update(emptyList(), 2.0).isEmpty())
        val next = tracking.update(listOf(face(100f)), 3.5).single()
        assertNotEquals(id, next.trackId)
        val back = tracking.update(listOf(face(100f)), 3.4).single()
        assertNotEquals("time going backwards resets", next.trackId, back.trackId)
    }
}
