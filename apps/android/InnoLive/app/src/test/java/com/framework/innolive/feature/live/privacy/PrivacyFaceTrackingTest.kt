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
        tracker.update(mapOf(0 to box), 1.35)
        tracker.accept(id, "registered", 1.35, 1.36)
        return tracker to id
    }

    @Test fun oneMatchDoesNotExemptButTwoCurrentMatchesDo() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        tracker.accept(id, "registered", 1.0, 1.0)
        assertTrue(tracker.verifiedResult(id, "registered", 1.0, 1.0, box).isEmpty())
        tracker.update(mapOf(0 to box), 1.35)
        tracker.accept(id, "registered", 1.35, 1.35)
        assertEquals(setOf(0), tracker.verifiedResult(id, "registered", 1.35, 1.35, box))
        assertTrue(tracker.verifiedResult(id, "registered", 2.2, 2.2, box).isEmpty())
    }

    @Test fun delayedResultNeverExemptsNewerFrameEvenWithin750ms() {
        val (tracker, id) = confirmed()
        assertTrue(tracker.verifiedResult(id, "registered", 1.35, 1.36, box).isEmpty())
        assertTrue(tracker.verifiedResult(id, "registered", 1.36, 1.35, box).isEmpty())
    }

    @Test fun replacementAtSamePositionIsProtectedUntilNewIdentityIsConfirmed() {
        val (tracker, id) = confirmed()
        tracker.update(mapOf(0 to box), 1.5)
        tracker.accept(id, null, 1.5, 1.5)
        assertTrue(tracker.verifiedResult(id, null, 1.5, 1.5, box).isEmpty())
        tracker.accept(id, "registered", 1.6, 1.6)
        assertTrue(tracker.verifiedResult(id, "registered", 1.6, 1.6, box).isEmpty())
    }

    @Test fun recognizedFaceElsewhereCannotExemptTrackedFace() {
        val (tracker, id) = confirmed()
        val other = PrivacySegmentation.Box(200f, 100f, 250f, 150f)
        assertTrue(tracker.verifiedResult(id, "registered", 1.36, 1.36, other).isEmpty())
        assertTrue(tracker.verifiedResult(id, "registered", 1.36, 1.36, null).isEmpty())
    }

    @Test fun overlapDisappearanceAndResetDiscardIdentity() {
        val (tracker, id) = confirmed()
        val overlap = PrivacySegmentation.Box(120f, 110f, 165f, 155f)
        tracker.update(mapOf(0 to box, 1 to overlap), 1.4)
        tracker.accept(id, "registered", 1.4, 1.4)
        assertTrue(tracker.verifiedResult(id, "registered", 1.4, 1.4, box).isEmpty())
        tracker.update(emptyMap(), 1.5)
        tracker.update(mapOf(0 to box), 1.6)
        assertTrue(tracker.verifiedResult(id, "registered", 1.6, 1.6, box).isEmpty())
        tracker.reset()
        assertNull(tracker.next(1.7))
    }

    @Test fun missingSampleNeverAuthorizesCurrentFrameOrRenewsLease() {
        val (tracker, id) = confirmed()
        tracker.accept(id, null, 1.5, 1.5, sampleAvailable = false)
        assertTrue(tracker.verifiedResult(id, null, 1.5, 1.5, box).isEmpty())
        assertTrue(tracker.verifiedResult(id, "registered", 2.1, 2.1, box).isEmpty())
    }

    @Test fun freshVerificationCanBeScheduledEveryFrameAfterConfirmation() {
        val (tracker, id) = confirmed()
        assertEquals(id, tracker.next(1.36, currentFrame = true)?.id)
        assertEquals(id, tracker.next(1.4, currentFrame = true)?.id)
        assertNull(tracker.next(1.4))
    }
}
