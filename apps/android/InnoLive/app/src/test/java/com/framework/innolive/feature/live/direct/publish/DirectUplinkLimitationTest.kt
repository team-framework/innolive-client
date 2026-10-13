package com.framework.innolive.feature.live.direct.publish

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.status.BroadcastUplinkLimitation
import com.framework.innolive.feature.live.status.BroadcastUplinkQualityTracker
import com.framework.innolive.feature.live.status.UplinkVideoStats
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectUplinkLimitationTest {
    private val limitation = DirectUplinkLimitation()

    private fun stats(
        fps: Double = 30.0,
        bitrateKbps: Int? = 5800,
        connected: Boolean = true,
        reconnects: Int = 0,
        slate: Boolean = false,
    ) = DirectPublishStats(
        targets = listOf(TargetPublishStats(BroadcastProvider.YOUTUBE, connected, bitrateKbps, reconnects)),
        fps = fps, droppedFrames = 0, width = 1920, height = 1080,
        targetFps = 30, targetVideoBitrateKbps = 6000, slateActive = slate,
    )

    @Test fun healthyPublishHasNoLimitation() {
        assertEquals("none", limitation.reason(stats()))
    }

    @Test fun lowEncoderFpsIsDeviceLimited() {
        assertEquals("cpu", limitation.reason(stats(fps = 12.7, bitrateKbps = 3500)))
    }

    @Test fun reconnectSinceLastSampleIsNetworkLimitedOnce() {
        assertEquals("none", limitation.reason(stats(reconnects = 1)))
        assertEquals("bandwidth", limitation.reason(stats(reconnects = 2)))
        assertEquals("none", limitation.reason(stats(reconnects = 2)))
    }

    @Test fun disconnectedOrLowBitrateTargetIsNetworkLimited() {
        assertEquals("bandwidth", limitation.reason(stats(connected = false, bitrateKbps = null)))
        assertEquals("bandwidth", limitation.reason(stats(bitrateKbps = 2500)))
    }

    @Test fun slateIsNotALimitation() {
        assertEquals("none", limitation.reason(stats(fps = 15.0, bitrateKbps = 400, slate = true)))
    }

    @Test fun trackerWarnsAfterRepeatedDeviceLimitation() {
        val tracker = BroadcastUplinkQualityTracker()
        repeat(BroadcastUplinkQualityTracker.SAMPLES_TO_WARN) {
            val sample = stats(fps = 12.7)
            tracker.record(UplinkVideoStats(limitation.reason(sample), sample.width, sample.height, sample.fps))
        }
        assertEquals(BroadcastUplinkLimitation.DEVICE, tracker.quality.limitation)
    }
}
