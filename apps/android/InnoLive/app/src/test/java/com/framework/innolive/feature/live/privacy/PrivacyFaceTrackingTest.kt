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

    @Test fun twoConfirmationsAuthorizeLaterFramesOnlyUntil500msFromCapture() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        tracker.accept(id, "registered", 1.0, 1.1)
        assertTrue(tracker.allowed(1.1).isEmpty())
        tracker.update(mapOf(0 to box), 1.35)
        tracker.accept(id, "registered", 1.35, 1.45)
        assertEquals(setOf(0), tracker.allowed(1.8))
        assertTrue(tracker.allowed(1.85).isEmpty())
    }

    @Test fun refreshedCacheUsesCaptureTimeRatherThanCompletionTime() {
        val (tracker, id) = confirmed()
        tracker.accept(id, "registered", 1.6, 1.8)
        assertEquals(setOf(0), tracker.allowed(2.09))
        assertTrue(tracker.allowed(2.1).isEmpty())
    }

    @Test fun resultDelayedBy500msCannotRenewOrGrantException() {
        val (tracker, id) = confirmed()
        tracker.accept(id, "registered", 1.5, 2.0)
        assertTrue(tracker.allowed(2.0).isEmpty())
        tracker.accept(id, "registered", 2.1, 2.1)
        assertTrue(tracker.allowed(2.1).isEmpty())
    }

    @Test fun replacementKeepsBoundedCacheUntilUnknownResultRevokesIt() {
        val (tracker, id) = confirmed()
        tracker.update(mapOf(0 to box), 1.5)
        assertEquals(setOf(0), tracker.allowed(1.5))
        tracker.accept(id, null, 1.5, 1.55)
        assertTrue(tracker.allowed(1.55).isEmpty())
        tracker.accept(id, "registered", 1.6, 1.6)
        assertTrue(tracker.allowed(1.6).isEmpty())
    }

    @Test fun differentRegisteredIdentityRequiresTwoNewConfirmations() {
        val (tracker, id) = confirmed()
        tracker.accept(id, "other", 1.5, 1.5)
        assertTrue(tracker.allowed(1.5).isEmpty())
        tracker.accept(id, "other", 1.8, 1.8)
        assertEquals(setOf(0), tracker.allowed(1.8))
    }

    @Test fun missingSampleKeepsOriginalCacheWithoutExtendingIt() {
        val (tracker, id) = confirmed()
        tracker.accept(id, null, 1.8, 1.8, sampleAvailable = false)
        assertEquals(setOf(0), tracker.allowed(1.84))
        assertTrue(tracker.allowed(1.85).isEmpty())
    }

    @Test fun confirmedFacesAreRecheckedAt250msIntervals() {
        val (tracker, id) = confirmed()
        assertEquals(id, tracker.next(1.4)?.id)
        assertNull(tracker.next(1.6))
        assertEquals(id, tracker.next(1.65)?.id)
    }

    @Test fun overlapDisappearanceAndResetDiscardIdentity() {
        val (tracker, id) = confirmed()
        val overlap = PrivacySegmentation.Box(120f, 110f, 165f, 155f)
        tracker.update(mapOf(0 to box, 1 to overlap), 1.4)
        tracker.accept(id, "registered", 1.4, 1.4)
        assertTrue(tracker.allowed(1.4).isEmpty())
        tracker.update(emptyMap(), 1.5)
        tracker.update(mapOf(0 to box), 1.6)
        assertTrue(tracker.allowed(1.6).isEmpty())
        tracker.reset()
        assertNull(tracker.next(1.7))
    }

    @Test fun slowFramesKeepTrackBut500msGapRejectsOldWork() {
        val (tracker, id) = confirmed()
        assertEquals(setOf(0), tracker.allowed(1.36))
        tracker.update(mapOf(0 to box), 1.85)
        tracker.accept(id, "registered", 1.7, 1.86)
        assertTrue(tracker.allowed(1.86).isEmpty())
        assertNotEquals(id, tracker.next(1.86)?.id)
    }

    @Test fun duplicateOrReorderedResultsCannotConfirmOrRestoreRevokedIdentity() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        repeat(2) { tracker.accept(id, "registered", 1.0, 1.1) }
        assertTrue(tracker.allowed(1.1).isEmpty())
        tracker.accept(id, "registered", 1.3, 1.35)
        assertEquals(setOf(0), tracker.allowed(1.35))
        tracker.accept(id, null, 1.4, 1.4)
        tracker.accept(id, "registered", 1.3, 1.45)
        assertTrue(tracker.allowed(1.45).isEmpty())
    }

    @Test fun sameIdentityOnTwoTracksIsAmbiguous() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box, 1 to PrivacySegmentation.Box(300f, 100f, 350f, 150f)), 1.0)
        val first = tracker.next(1.0)!!.id
        val second = tracker.next(1.0)!!.id
        for (id in listOf(first, second)) {
            tracker.accept(id, "registered", 1.0, 1.01)
            tracker.accept(id, "registered", 1.3, 1.31)
        }
        assertTrue(tracker.allowed(1.31).isEmpty())
    }

    @Test fun recognitionGeometryAndInvalidTimeCannotAuthorizeAnException() {
        val (tracker, id) = confirmed()
        assertTrue(tracker.recognitionMatches(id, box))
        assertFalse(tracker.recognitionMatches(id, PrivacySegmentation.Box(200f, 100f, 250f, 150f)))
        assertTrue(tracker.allowed(Double.NaN).isEmpty())
        tracker.update(mapOf(0 to box), Double.NaN)
        assertTrue(tracker.allowed(1.4).isEmpty())
    }
}
