package com.framework.innolive.feature.live.direct.broadcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlateReasonSetTest {
    @Test fun emptySetSendsCamera() {
        assertFalse(SlateReasonSet().isActive)
    }

    @Test fun returningToForegroundKeepsUserPause() {
        val paused = SlateReasonSet()
            .with(SlateReason.USER_PAUSE, true)
            .with(SlateReason.BACKGROUND, true)
            .with(SlateReason.BACKGROUND, false)

        assertTrue(paused.isActive)
        assertEquals(setOf(SlateReason.USER_PAUSE), paused.reasons)
    }

    @Test fun slateEndsOnlyAfterEveryReasonClears() {
        val both = SlateReasonSet()
            .with(SlateReason.BACKGROUND, true)
            .with(SlateReason.CAMERA_UNAVAILABLE, true)

        assertTrue(both.with(SlateReason.BACKGROUND, false).isActive)
        assertFalse(both.with(SlateReason.BACKGROUND, false).with(SlateReason.CAMERA_UNAVAILABLE, false).isActive)
    }

    @Test fun repeatedChangesAreIdempotent() {
        val once = SlateReasonSet().with(SlateReason.BACKGROUND, true)
        assertEquals(once, once.with(SlateReason.BACKGROUND, true))
        assertEquals(SlateReasonSet(), SlateReasonSet().with(SlateReason.BACKGROUND, false))
    }
}
