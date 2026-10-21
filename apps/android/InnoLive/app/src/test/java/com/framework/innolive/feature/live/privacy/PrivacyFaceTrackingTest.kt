package com.framework.innolive.feature.live.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyFaceTrackingTest {
    private val box = PrivacySegmentation.Box(100f, 100f, 150f, 150f)
    private val tracker = PrivacyFaceTracking()
    private var frame = 0
    private val now get() = frame / 30.0
    private val face = PrivacyFaceTracking.Face(0, 1, box)

    /** Advance one 30fps frame with [faces]; BoT-SORT keeps [live] IDs (null: not provided). */
    private fun step(vararg faces: PrivacyFaceTracking.Face = arrayOf(face), live: Set<Int>? = setOf(1)) {
        frame++
        tracker.update(faces.toList(), now, live)
    }

    private fun answer(track: PrivacyFaceTracking.Track, match: String?, sampleAvailable: Boolean = true) =
        tracker.accept(track.id, match, checkNotNull(track.pendingSeconds), now, sampleAvailable)

    private fun approve(): PrivacyFaceTracking.Track {
        step()
        val track = checkNotNull(tracker.next(now))
        answer(track, "registered")
        assertEquals(setOf(0), tracker.allowed())
        return track
    }

    @Test fun oneMatchApprovesAndAnApprovedTrackIsNeverCheckedAgain() {
        approve()
        repeat(200) {
            step()
            assertNull("no revalidation after approval", tracker.next(now))
            assertEquals(setOf(0), tracker.allowed())
        }
    }

    @Test fun unapprovedFaceIsRetriedTwiceThenEvery60Frames() {
        val checks = ArrayList<Int>()
        repeat(140) {
            step()
            tracker.next(now)?.let { checks += frame; answer(it, null) }
        }
        assertEquals(listOf(1, 6, 11, 71, 131), checks)
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun approvalFollowsTheTrackerIdThroughALongDetectionGap() {
        approve()
        repeat(20) { step(*emptyArray(), live = setOf(1)) }
        assertTrue("a missing face has nothing to exempt", tracker.allowed().isEmpty())
        step()
        assertEquals("the restored ID keeps its approval", setOf(0), tracker.allowed())
        assertNull(tracker.next(now))
    }

    @Test fun approvalEndsWhenTheTrackerRetiresTheId() {
        approve()
        step(*emptyArray(), live = emptySet())
        step()
        assertTrue(tracker.allowed().isEmpty())
        assertNotNull("the returning ID is checked again", tracker.next(now))
    }

    @Test fun unapprovedDecisionIsForgottenAfterThreeMissingFrames() {
        step()
        answer(checkNotNull(tracker.next(now)), null)
        repeat(3) { step(*emptyArray()) }
        step()
        assertNull("still retained, so its retry is not due yet", tracker.next(now))
        repeat(4) { step(*emptyArray()) }
        step()
        assertNotNull("forgotten, so it is checked at once", tracker.next(now))
    }

    @Test fun heldApprovedFaceStaysExemptAndIsNeverRecognized() {
        approve()
        repeat(50) {
            step(PrivacyFaceTracking.Face(0, 1, box, held = true))
            assertEquals(setOf(0), tracker.allowed())
            assertNull(tracker.next(now))
        }
    }

    @Test fun onlyDeletingTheMatchedRegisteredFaceEndsTheApproval() {
        approve()
        tracker.retain(setOf("registered", "added"))
        step()
        assertEquals(setOf(0), tracker.allowed())
        tracker.retain(setOf("added"))
        step()
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun negativeOrMissingSampleResultsDoNotApprove() {
        step()
        answer(checkNotNull(tracker.next(now)), null, sampleAvailable = false)
        assertTrue(tracker.allowed().isEmpty())
        repeat(5) { step() }
        answer(checkNotNull(tracker.next(now)), null)
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun lostResultTimesOutAndIsCheckedAgain() {
        step()
        val track = checkNotNull(tracker.next(now))
        repeat(29) { step(); assertNull(tracker.next(now)) }
        repeat(2) { step() }
        tracker.accept(track.id, "registered", checkNotNull(track.pendingSeconds), now)
        assertTrue("the late answer is ignored", tracker.allowed().isEmpty())
        assertNotNull(tracker.next(now))
    }

    @Test fun cancelledSubmissionIsScheduledAgain() {
        step()
        val track = checkNotNull(tracker.next(now))
        tracker.cancel(track.id)
        assertEquals(track.id, tracker.next(now)?.id)
    }

    @Test fun eachTrackIsApprovedIndependentlyEvenWhenOverlapping() {
        val near = PrivacyFaceTracking.Face(1, 2, PrivacySegmentation.Box(120f, 110f, 165f, 155f))
        step(face, near, live = setOf(1, 2))
        while (true) { val track = tracker.next(now) ?: break; answer(track, "registered") }
        assertEquals(setOf(0, 1), tracker.allowed())
    }

    @Test fun anotherTrackIdStartsUnapproved() {
        approve()
        step(PrivacyFaceTracking.Face(0, 2, box), live = setOf(1, 2))
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun movingTrackKeepsItsApproval() {
        approve()
        step(PrivacyFaceTracking.Face(0, 1, PrivacySegmentation.Box(300f, 100f, 350f, 150f)))
        assertEquals(setOf(0), tracker.allowed())
    }

    @Test fun oneSecondGapStartsOver() {
        approve()
        frame += 30
        step()
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun untrackedFaceIsNeverChecked() {
        step(PrivacyFaceTracking.Face(0, PrivacySegmentation.NO_TRACK, box))
        assertNull(tracker.next(now))
    }

    @Test fun recognizedFaceElsewhereCannotMatchTrackedFace() {
        step()
        val track = checkNotNull(tracker.next(now))
        assertTrue(tracker.recognitionMatches(track.id, box))
        assertFalse(tracker.recognitionMatches(track.id, PrivacySegmentation.Box(200f, 100f, 250f, 150f)))
        assertFalse(tracker.recognitionMatches(track.id, PrivacySegmentation.Box(Float.NaN, 100f, 150f, 150f)))
    }

    @Test fun faceTheCallerCannotRecognizeNeverBlocksTheOthers() {
        val small = PrivacyFaceTracking.Face(1, 2, PrivacySegmentation.Box(300f, 100f, 310f, 110f))
        step(face, small, live = setOf(1, 2))
        val first = checkNotNull(tracker.next(now) { it.trackId == 1 })
        assertEquals(1, first.trackId)
        assertNull("the small face is skipped, not chosen and cancelled", tracker.next(now) { it.trackId == 1 })
    }
}
