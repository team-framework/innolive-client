package com.framework.innolive.feature.live.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyFaceTrackingTest {
    private val box = PrivacySegmentation.Box(100f, 100f, 150f, 150f)
    private fun confirmed(): Pair<PrivacyFaceTracking, String> {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        tracker.accept(id, "registered", 1.0, 1.01)
        listOf(1.1, 1.2, 1.3).forEach { tracker.update(mapOf(0 to box), it) }
        assertEquals(id, tracker.next(1.3)!!.id)
        tracker.accept(id, "registered", 1.3, 1.4)
        return tracker to id
    }

    @Test fun twoConfirmationsPermitDelayedResultsUntilSourceFrameDeadline() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        tracker.accept(id, "registered", 1.0, 1.1)
        assertTrue(tracker.allowed(1.1).isEmpty())
        listOf(1.1, 1.2, 1.3).forEach { tracker.update(mapOf(0 to box), it) }
        tracker.accept(id, "registered", 1.3, 1.4)
        assertEquals(setOf(0), tracker.allowed(1.4))
        assertEquals(setOf(0), tracker.allowed(2.049))
        assertTrue(tracker.allowed(2.05).isEmpty())
    }

    @Test fun confirmedFacesAreRecheckedAtMinimum250msInterval() {
        val (tracker, id) = confirmed()
        assertNull(tracker.next(1.5))
        assertEquals(id, tracker.next(1.55)?.id)
        assertNull(tracker.next(1.7))
        assertEquals(id, tracker.next(1.8)?.id)
    }

    @Test fun oldestUnscheduledFaceGetsWorkerBeforeRecentlyCheckedFace() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box, 1 to PrivacySegmentation.Box(200f, 100f, 250f, 150f)), 1.0)
        assertEquals(0, tracker.next(1.0)?.index)
        assertEquals(1, tracker.next(1.1)?.index)
        assertNull(tracker.next(1.2))
        assertEquals(0, tracker.next(1.3)?.index)
    }

    @Test fun samePositionKeepsLeaseUntilUnknownResultRevokesIt() {
        val (tracker, id) = confirmed()
        tracker.update(mapOf(0 to box), 1.4)
        assertEquals(setOf(0), tracker.allowed(1.4))
        tracker.accept(id, null, 1.4, 1.5)
        assertTrue(tracker.allowed(1.5).isEmpty())
        tracker.accept(id, "registered", 1.6, 1.6)
        assertTrue(tracker.allowed(1.6).isEmpty())
    }

    @Test fun differentRegisteredIdentityNeedsTwoNewConfirmations() {
        val (tracker, id) = confirmed()
        tracker.accept(id, "other", 1.4, 1.4)
        assertTrue(tracker.allowed(1.4).isEmpty())
        tracker.accept(id, "other", 1.7, 1.7)
        assertEquals(setOf(0), tracker.allowed(1.7))
    }

    @Test fun missingSampleKeepsOriginalDeadlineWithoutRenewingLease() {
        val (tracker, id) = confirmed()
        tracker.accept(id, null, 1.9, 1.9, sampleAvailable = false)
        assertEquals(setOf(0), tracker.allowed(2.049))
        assertTrue(tracker.allowed(2.05).isEmpty())
    }

    @Test fun staleRecognitionCancelsLeaseAndCannotServeAsFirstConfirmation() {
        val (tracker, id) = confirmed()
        tracker.accept(id, "registered", 1.3, 2.051)
        assertTrue(tracker.allowed(2.051).isEmpty())
        tracker.accept(id, "registered", 2.1, 2.1)
        assertTrue(tracker.allowed(2.1).isEmpty())
    }

    @Test fun overlapDisappearanceAndResetDiscardIdentityAndPendingResults() {
        val (tracker, id) = confirmed()
        val overlap = PrivacySegmentation.Box(120f, 110f, 165f, 155f)
        tracker.update(mapOf(0 to box, 1 to overlap), 1.4)
        tracker.accept(id, "registered", 1.4, 1.4)
        assertTrue(tracker.allowed(1.4).isEmpty())
        tracker.update(emptyMap(), 1.5)
        tracker.update(mapOf(0 to box), 1.6)
        tracker.accept(id, "registered", 1.6, 1.6)
        assertTrue(tracker.allowed(1.6).isEmpty())
        tracker.reset()
        assertNull(tracker.next(1.7))
    }

    @Test fun frameGapOf200msDiscardsPendingTrack() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 0.0)
        val id = tracker.next(0.0)!!.id
        tracker.accept(id, "registered", 0.0, 0.0)
        tracker.update(mapOf(0 to box), .2)
        assertNotEquals(id, tracker.next(.2)?.id)
        tracker.accept(id, "registered", .2, .2)
        assertTrue(tracker.allowed(.2).isEmpty())
    }

    @Test fun slowFramesFollowIOSResetPolicyAndCannotAccumulateConfirmations() {
        val tracker = PrivacyFaceTracking()
        repeat(8) { frame ->
            val time = frame * .25
            tracker.update(mapOf(0 to box), time)
            val id = tracker.next(time)!!.id
            tracker.accept(id, "registered", time, time)
            assertTrue(tracker.allowed(time).isEmpty())
        }
    }

    @Test fun duplicateRegisteredIdentityOnTwoTracksDeniesBoth() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box, 1 to PrivacySegmentation.Box(200f, 100f, 250f, 150f)), 1.0)
        val first = tracker.next(1.0)!!.id
        val second = tracker.next(1.0)!!.id
        listOf(first, second).forEach {
            tracker.accept(it, "registered", 1.0, 1.0)
            tracker.accept(it, "registered", 1.3, 1.3)
        }
        assertTrue(tracker.allowed(1.3).isEmpty())
    }

    @Test fun recognizedFaceElsewhereCannotMatchTrackedFace() {
        val (tracker, id) = confirmed()
        assertTrue(tracker.recognitionMatches(id, box))
        assertFalse(tracker.recognitionMatches(id, PrivacySegmentation.Box(200f, 100f, 250f, 150f)))
        assertFalse(tracker.recognitionMatches(id, PrivacySegmentation.Box(Float.NaN, 100f, 150f, 150f)))
    }
}
