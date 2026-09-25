package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFaceTrackingTest {
    private val box = PrivacySegmentation.Box(100f, 100f, 150f, 150f)

    @Test fun oneMatchDoesNotExemptButTwoRecentMatchesDo() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val track = tracker.next(1.0)!!
        tracker.accept(track.id, "registered", 1.0, 1.1)
        assertTrue(tracker.allowed(1.1).isEmpty())
        tracker.update(mapOf(0 to box), 1.15)
        tracker.accept(track.id, "registered", 1.15, 1.2)
        assertEquals(setOf(0), tracker.allowed(1.2))
        assertTrue(tracker.allowed(1.9).isEmpty())
    }

    @Test fun overlapDisappearanceAndResetRevokeExemption() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        tracker.accept(id, "registered", 1.0, 1.01)
        tracker.update(mapOf(0 to box), 1.1)
        tracker.accept(id, "registered", 1.1, 1.11)
        assertEquals(setOf(0), tracker.allowed(1.11))
        val overlapping = PrivacySegmentation.Box(120f, 110f, 165f, 155f)
        tracker.update(mapOf(0 to box, 1 to overlapping), 1.15)
        assertTrue(tracker.allowed(1.15).isEmpty())
        tracker.accept(id, "registered", 1.1, 1.16)
        assertTrue(tracker.allowed(1.16).isEmpty())
        tracker.reset()
        assertNull(tracker.next(1.2))
    }

    @Test fun missingSampleDoesNotExtendLeaseAndMismatchRevokesIt() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 2.0)
        val id = tracker.next(2.0)!!.id
        tracker.accept(id, "registered", 2.0, 2.02)
        tracker.update(mapOf(0 to box), 2.1)
        tracker.accept(id, "registered", 2.1, 2.12)
        tracker.accept(id, null, 2.2, 2.21, sampleAvailable = false)
        assertEquals(setOf(0), tracker.allowed(2.3))
        tracker.accept(id, null, 2.3, 2.31, sampleAvailable = true)
        assertTrue(tracker.allowed(2.31).isEmpty())
        assertFalse(tracker.allowed(2.5).contains(0))
    }
}
