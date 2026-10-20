package com.framework.innolive.feature.live.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyFaceTrackingTest {
    private val box = PrivacySegmentation.Box(100f, 100f, 150f, 150f)
    private val tracker = PrivacyFaceTracking()
    private var frame = 0
    private val now get() = frame / 30.0

    /** Advance one 30fps frame with [faces] (default: track 1 detected at [box]). */
    private fun step(vararg faces: PrivacyFaceTracking.Face = arrayOf(PrivacyFaceTracking.Face(0, 1, box))) {
        frame++
        tracker.update(faces.toList(), now)
    }

    private fun stepUntilDue(): PrivacyFaceTracking.Track {
        repeat(100) { step(); tracker.next(now)?.let { return it } }
        error("no recognition was scheduled")
    }

    private fun answer(track: PrivacyFaceTracking.Track, match: String?, sampleAvailable: Boolean = true) =
        tracker.accept(track.id, match, checkNotNull(track.pendingSeconds), now, sampleAvailable)

    /** Two matching results, the second one after the five-frame quick retry. */
    private fun exempt(): PrivacyFaceTracking.Track {
        step()
        val first = checkNotNull(tracker.next(now))
        answer(first, "registered")
        assertTrue("one match is not enough", tracker.allowed().isEmpty())
        val second = stepUntilDue()
        assertEquals(6, frame)
        answer(second, "registered")
        assertEquals(setOf(0), tracker.allowed())
        return second
    }

    @Test fun firstExemptionNeedsTwoMatchesFiveFramesApart() { exempt() }

    @Test fun exemptTrackStaysExemptWhileRevalidatingEvery30Frames() {
        exempt()
        val recheck = stepUntilDue()
        assertEquals(36, frame)
        repeat(10) { step(); assertEquals("pending revalidation keeps the exemption", setOf(0), tracker.allowed()) }
        assertNull("one recognition in flight per track", tracker.next(now))
        answer(recheck, "registered")
        assertEquals(setOf(0), tracker.allowed())
    }

    @Test fun negativeRevalidationRevokesAtOnce() {
        exempt()
        answer(stepUntilDue(), null)
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun missingSampleKeepsTheDecision() {
        exempt()
        answer(stepUntilDue(), null, sampleAvailable = false)
        assertEquals(setOf(0), tracker.allowed())
    }

    @Test fun exemptionEndsWhenNoRevalidationCanRun() {
        exempt()
        repeat(PrivacyFaceTracking.CONFIRMATION_LIMIT_FRAMES) { step() }
        assertEquals("checks never scheduled, but still within the limit", setOf(0), tracker.allowed())
        step()
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun repeatedMissingSamplesCannotRenewTheExemption() {
        exempt()
        repeat(PrivacyFaceTracking.CONFIRMATION_LIMIT_FRAMES + 1) {
            step()
            tracker.next(now)?.let { answer(it, null, sampleAvailable = false) }
        }
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun decisionSurvivesThreeMissingFramesButNotFour() {
        exempt()
        repeat(3) { step(*emptyArray()) }
        assertTrue("a missing face has nothing to exempt", tracker.allowed().isEmpty())
        step()
        assertEquals(setOf(0), tracker.allowed())
        repeat(4) { step(*emptyArray()) }
        step()
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun heldFaceKeepsExemptionUntilItsCheckIsDueAndIsNeverRecognized() {
        exempt()
        val held = PrivacyFaceTracking.Face(0, 1, box, held = true)
        repeat(29) { step(held); assertEquals(setOf(0), tracker.allowed()); assertNull(tracker.next(now)) }
        step(held)
        assertTrue("a held face due for a check needs a fresh match", tracker.allowed().isEmpty())
        assertNull(tracker.next(now))
    }

    @Test fun overlapRevokesAndNeedsTwoNewMatches() {
        exempt()
        step(PrivacyFaceTracking.Face(0, 1, box), PrivacyFaceTracking.Face(1, 2, PrivacySegmentation.Box(120f, 110f, 165f, 155f)))
        assertTrue(tracker.allowed().isEmpty())
        assertNull("an overlapping face is not recognized", tracker.next(now))
        step()
        val first = checkNotNull(tracker.next(now))
        answer(first, "registered")
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun unknownFaceIsRetriedTwiceThenEvery30Frames() {
        val checks = ArrayList<Int>()
        repeat(80) {
            step()
            tracker.next(now)?.let { checks += frame; answer(it, null) }
        }
        assertEquals(listOf(1, 6, 11, 41, 71), checks)
    }

    @Test fun lostResultTimesOutAndRevokes() {
        exempt()
        val recheck = stepUntilDue()
        repeat(29) { step() }
        assertEquals(setOf(0), tracker.allowed())
        repeat(2) { step() }
        assertTrue("no answer within a second needs a fresh match", tracker.allowed().isEmpty())
        tracker.accept(recheck.id, "registered", checkNotNull(recheck.pendingSeconds), now)
        assertTrue("the late answer is ignored", tracker.allowed().isEmpty())
        assertNotNull(tracker.next(now))
    }

    @Test fun cancelledSubmissionIsScheduledAgain() {
        step()
        val track = checkNotNull(tracker.next(now))
        tracker.cancel(track.id)
        assertEquals(track.id, tracker.next(now)?.id)
    }

    @Test fun anotherTrackIdStartsUnknown() {
        exempt()
        step(PrivacyFaceTracking.Face(0, 2, PrivacySegmentation.Box(300f, 100f, 350f, 150f)))
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun movingTrackKeepsItsDecision() {
        exempt()
        step(PrivacyFaceTracking.Face(0, 1, PrivacySegmentation.Box(300f, 100f, 350f, 150f)))
        assertEquals(setOf(0), tracker.allowed())
    }

    @Test fun duplicateRegisteredIdentityOnTwoTracksDeniesBoth() {
        val other = PrivacySegmentation.Box(300f, 100f, 350f, 150f)
        val faces = arrayOf(PrivacyFaceTracking.Face(0, 1, box), PrivacyFaceTracking.Face(1, 2, other))
        repeat(12) {
            step(*faces)
            while (true) { val track = tracker.next(now) ?: break; answer(track, "registered") }
        }
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun oneSecondGapStartsOverButShorterStallsKeepTheDecision() {
        exempt()
        frame += 9
        step()
        assertEquals("a 300ms stall keeps the exemption", setOf(0), tracker.allowed())
        frame += 30
        step()
        assertTrue(tracker.allowed().isEmpty())
    }

    @Test fun untrackedFaceIsNeverCheckedButStillBreaksIsolation() {
        exempt()
        step(PrivacyFaceTracking.Face(0, 1, box),
            PrivacyFaceTracking.Face(1, PrivacySegmentation.NO_TRACK, PrivacySegmentation.Box(120f, 110f, 165f, 155f)))
        assertTrue(tracker.allowed().isEmpty())
        val alone = PrivacyFaceTracking()
        alone.update(listOf(PrivacyFaceTracking.Face(0, PrivacySegmentation.NO_TRACK, box)), 1.0)
        assertNull(alone.next(1.0))
    }

    @Test fun recognizedFaceElsewhereCannotMatchTrackedFace() {
        val track = exempt()
        assertTrue(tracker.recognitionMatches(track.id, box))
        assertFalse(tracker.recognitionMatches(track.id, PrivacySegmentation.Box(200f, 100f, 250f, 150f)))
        assertFalse(tracker.recognitionMatches(track.id, PrivacySegmentation.Box(Float.NaN, 100f, 150f, 150f)))
    }
}
