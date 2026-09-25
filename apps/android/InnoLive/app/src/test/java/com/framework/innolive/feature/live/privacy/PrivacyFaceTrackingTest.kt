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
        assertTrue(tracker.verifyCurrentFrame(1.1) { true }.isEmpty())
        tracker.update(mapOf(0 to box), 1.15)
        tracker.accept(track.id, "registered", 1.15, 1.2)
        assertEquals(setOf(0), tracker.verifyCurrentFrame(1.2) { true })
        assertTrue(tracker.verifyCurrentFrame(2.0) { true }.isEmpty())
    }

    @Test fun overlapDisappearanceAndResetRevokeExemption() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        tracker.accept(id, "registered", 1.0, 1.01)
        tracker.update(mapOf(0 to box), 1.1)
        tracker.accept(id, "registered", 1.1, 1.11)
        assertEquals(setOf(0), tracker.verifyCurrentFrame(1.11) { true })
        val overlapping = PrivacySegmentation.Box(120f, 110f, 165f, 155f)
        tracker.update(mapOf(0 to box, 1 to overlapping), 1.15)
        assertTrue(tracker.verifyCurrentFrame(1.15) { true }.isEmpty())
        tracker.accept(id, "registered", 1.1, 1.16)
        assertTrue(tracker.verifyCurrentFrame(1.16) { true }.isEmpty())
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
        assertEquals(setOf(0), tracker.verifyCurrentFrame(2.3) { true })
        tracker.accept(id, null, 2.3, 2.31, sampleAvailable = true)
        assertTrue(tracker.verifyCurrentFrame(2.31) { true }.isEmpty())
        assertFalse(tracker.verifyCurrentFrame(2.5) { true }.contains(0))
    }

    @Test fun replacementAtSamePositionIsProtectedUntilNewIdentityIsConfirmed() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        tracker.accept(id, "registered", 1.0, 1.01)
        tracker.update(mapOf(0 to box), 1.3)
        tracker.accept(id, "registered", 1.3, 1.31)
        assertEquals(setOf(0), tracker.verifyCurrentFrame(1.31) { true })

        // IoU is unchanged, but the current frame's embedding belongs to someone else.
        tracker.update(mapOf(0 to box), 1.5)
        assertTrue(tracker.verifyCurrentFrame(1.5) { false }.isEmpty())
        assertTrue(tracker.verifyCurrentFrame(1.6) { true }.isEmpty())
    }

    @Test fun twoConfirmationsRemainPossibleBelowFiveFramesPerSecond() {
        val tracker = PrivacyFaceTracking()
        tracker.update(mapOf(0 to box), 1.0)
        val id = tracker.next(1.0)!!.id
        tracker.accept(id, "registered", 1.0, 1.01)
        tracker.update(mapOf(0 to box), 1.35)
        tracker.accept(id, "registered", 1.35, 1.36)
        assertEquals(setOf(0), tracker.verifyCurrentFrame(1.36) { true })
    }
}
