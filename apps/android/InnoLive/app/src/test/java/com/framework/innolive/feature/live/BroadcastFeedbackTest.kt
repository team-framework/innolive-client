package com.framework.innolive.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastFeedbackTest {
    @Test fun connectionProblemWinsOverEveryOtherMessage() {
        val shown = primaryBroadcastFeedback(BroadcastFeedbackKind.entries.toSet())

        assertEquals(BroadcastFeedbackKind.CONNECTION, shown)
    }

    @Test fun errorsComeBeforeNotices() {
        val shown = primaryBroadcastFeedback(
            setOf(
                BroadcastFeedbackKind.BROADCAST_STATUS,
                BroadcastFeedbackKind.NOTIFICATIONS_HIDDEN,
                BroadcastFeedbackKind.RECORDING,
                BroadcastFeedbackKind.ANONYMIZATION_ERROR,
            ),
        )

        assertEquals(BroadcastFeedbackKind.ANONYMIZATION_ERROR, shown)
    }

    @Test fun plainStatusShowsOnlyWhenNothingElseIsPresent() {
        assertEquals(
            BroadcastFeedbackKind.NOTIFICATIONS_HIDDEN,
            primaryBroadcastFeedback(setOf(BroadcastFeedbackKind.BROADCAST_STATUS, BroadcastFeedbackKind.NOTIFICATIONS_HIDDEN)),
        )
        assertEquals(
            BroadcastFeedbackKind.BROADCAST_STATUS,
            primaryBroadcastFeedback(setOf(BroadcastFeedbackKind.BROADCAST_STATUS)),
        )
    }

    @Test fun nothingToShowLeavesTheSlotEmpty() {
        assertNull(primaryBroadcastFeedback(emptySet()))
    }
}
