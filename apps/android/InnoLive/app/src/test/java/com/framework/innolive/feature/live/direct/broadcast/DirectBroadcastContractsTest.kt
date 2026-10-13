package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBroadcastContractsTest {
    @Test fun planDefaultsToSingleYouTube720p() {
        val plan = DirectBroadcastPlan()
        assertEquals(DirectPublishResolution.HD_720, plan.resolution)
        assertEquals(setOf(BroadcastProvider.YOUTUBE), plan.providers)
    }

    @Test fun planRequiresAtLeastOneTarget() {
        assertThrows(IllegalArgumentException::class.java) { DirectBroadcastPlan(providers = emptySet()) }
    }

    @Test fun uiStateStartsWithoutLocalDirectState() {
        val state = DirectBroadcastUiState()
        assertTrue(state.targets.isEmpty())
        assertEquals(MicrophoneStatus.OK, state.microphone)
        assertTrue(state.slate.isEmpty())
    }
}
