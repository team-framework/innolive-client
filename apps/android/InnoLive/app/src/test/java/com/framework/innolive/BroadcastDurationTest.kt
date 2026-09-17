package com.framework.innolive

import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.formatBroadcastDuration
import com.framework.innolive.feature.live.nextBroadcastStartedAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastDurationTest {
    @Test
    fun durationIsFormattedAsHoursMinutesAndSeconds() {
        assertEquals("00:00:00", formatBroadcastDuration(0L))
        assertEquals("00:01:01", formatBroadcastDuration(61_999L))
        assertEquals("01:01:01", formatBroadcastDuration(3_661_000L))
        assertEquals("00:00:00", formatBroadcastDuration(-1L))
    }

    @Test
    fun liveStartsTimerAndPauseResumeKeepsOriginalStart() {
        val startedAt = nextBroadcastStartedAt(null, BroadcastState.LIVE, 1_000L)

        assertEquals(1_000L, startedAt)
        assertEquals(1_000L, nextBroadcastStartedAt(startedAt, BroadcastState.PAUSED, 5_000L))
        assertEquals(1_000L, nextBroadcastStartedAt(startedAt, BroadcastState.LIVE, 9_000L))
    }

    @Test
    fun stoppingKeepsTimerUntilBroadcastBecomesIdle() {
        assertEquals(1_000L, nextBroadcastStartedAt(1_000L, BroadcastState.STOPPING, 5_000L))
        assertNull(nextBroadcastStartedAt(1_000L, BroadcastState.IDLE, 6_000L))
        assertNull(nextBroadcastStartedAt(1_000L, BroadcastState.FAILED, 6_000L))
    }
}
