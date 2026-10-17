package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test

class AnalyzerFrameCadenceTest {
    private val interval = 1_000_000_000L / 30

    @Test fun cameraTimestampGapsCountFramesDroppedBeforeTheAnalyzer() {
        val cadence = AnalyzerFrameCadence()
        // 33ms, 67ms, 100ms 간격은 각각 0, 1, 2장을 건너뛴 것입니다.
        for (timestamp in listOf(0L, interval, interval * 3, interval * 6)) cadence.record(timestamp, 5_000_000L, true)
        val sample = cadence.drain()
        assertEquals(4, sample.frames)
        assertEquals(3, sample.cameraSkipped)
        assertEquals(0, sample.overBudget)
        assertEquals(5.0, sample.processingAvgMs!!, 1e-9)
    }

    @Test fun cameraRestartGapsAreNotCountedAsDrops() {
        val cadence = AnalyzerFrameCadence()
        cadence.record(5_000_000_000L, 1L, true)
        cadence.record(1_000_000L, 1L, true)
        cadence.record(1_000_000L + 2_000_000_000L, 1L, true)
        assertEquals(0, cadence.drain().cameraSkipped)
    }

    @Test fun processingOverOneFrameIntervalCountsAsOverBudget() {
        val cadence = AnalyzerFrameCadence()
        cadence.record(0L, interval + 1, false)
        cadence.record(interval, interval, true)
        val sample = cadence.drain()
        assertEquals(1, sample.overBudget)
        assertEquals((interval + 1) / 1e6, sample.processingMaxMs, 1e-9)
        assertEquals(1, sample.deliveredTotal)
    }

    @Test fun drainResetsTheIntervalButKeepsDeliveredTotalForEncoderComparison() {
        val cadence = AnalyzerFrameCadence()
        cadence.record(0L, 1L, true)
        cadence.drain()
        cadence.record(interval, 1L, true)
        val sample = cadence.drain()
        assertEquals(1, sample.frames)
        assertEquals(0, sample.cameraSkipped)
        assertEquals(2, sample.deliveredTotal)
        assertNull(cadence.drain().processingAvgMs)
    }
}
