package com.framework.innolive.feature.live.status

import com.framework.innolive.R
import com.framework.innolive.feature.live.SessionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastLiveStatusTest {
    @Test
    fun uplinkQualityIsMeasuringUntilTheFirstSample() {
        val tracker = BroadcastUplinkQualityTracker()

        assertEquals(BroadcastUplinkQuality.Measuring, tracker.quality)
        assertNull(tracker.quality.summary)
    }

    @Test
    fun uplinkSummaryUsesShortEdgeAndRoundedFrameRate() {
        val tracker = BroadcastUplinkQualityTracker()

        assertEquals("720p · 30fps", tracker.record(sample(width = 720, height = 1280, fps = 29.6)).summary)
        assertEquals("1080p · 24fps", tracker.record(sample(width = 1920, height = 1080, fps = 24.2)).summary)
        assertEquals("720p", tracker.record(sample(width = 1280, height = 720, fps = null)).summary)
    }

    @Test
    fun networkLimitWarnsOnlyAfterThreeConsecutiveSamples() {
        val tracker = BroadcastUplinkQualityTracker()

        assertNull(tracker.record(sample(reason = "bandwidth")).limitation)
        assertNull(tracker.record(sample(reason = "bandwidth")).limitation)
        assertEquals(BroadcastUplinkLimitation.NETWORK, tracker.record(sample(reason = "bandwidth")).limitation)
    }

    @Test
    fun shortLimitBurstAtStartDoesNotWarn() {
        val tracker = BroadcastUplinkQualityTracker()

        listOf("bandwidth", "bandwidth", "none", "bandwidth", "bandwidth").forEach { reason ->
            assertNull(reason, tracker.record(sample(reason = reason)).limitation)
        }
    }

    @Test
    fun cpuLimitWarnsAsDeviceLoad() {
        val tracker = BroadcastUplinkQualityTracker()

        tracker.record(sample(reason = "cpu"))
        tracker.record(sample(reason = "cpu"))
        assertEquals(BroadcastUplinkLimitation.DEVICE, tracker.record(sample(reason = "cpu")).limitation)
    }

    @Test
    fun shownWarningFollowsTheLatestLimitReason() {
        val tracker = BroadcastUplinkQualityTracker()
        repeat(3) { tracker.record(sample(reason = "bandwidth")) }

        assertEquals(BroadcastUplinkLimitation.DEVICE, tracker.record(sample(reason = "cpu")).limitation)
    }

    @Test
    fun warningClearsAfterTwoUnlimitedSamples() {
        val tracker = BroadcastUplinkQualityTracker()
        repeat(3) { tracker.record(sample(reason = "bandwidth")) }

        assertEquals(BroadcastUplinkLimitation.NETWORK, tracker.record(sample(reason = "none")).limitation)
        assertNull(tracker.record(sample(reason = "none")).limitation)
    }

    @Test
    fun unknownLimitReasonIsNotShownAsWarning() {
        val tracker = BroadcastUplinkQualityTracker()

        repeat(4) { assertNull(tracker.record(sample(reason = "other")).limitation) }
    }

    @Test
    fun resetReturnsToMeasuringAndForgetsTheStreak() {
        val tracker = BroadcastUplinkQualityTracker()
        tracker.record(sample(reason = "bandwidth"))
        tracker.record(sample(reason = "bandwidth"))

        tracker.reset()

        assertEquals(BroadcastUplinkQuality.Measuring, tracker.quality)
        assertNull(tracker.record(sample(reason = "bandwidth")).limitation)
    }

    @Test
    fun outboundStatsKeepOnlyThePanelValues() {
        val parsed = UplinkVideoStats.from(
            mapOf(
                "kind" to "video",
                "qualityLimitationReason" to "bandwidth",
                "frameWidth" to 1280L,
                "frameHeight" to 720L,
                "framesPerSecond" to 30.0,
            ),
        )

        assertEquals(UplinkVideoStats("bandwidth", 1280, 720, 30.0), parsed)
        assertEquals(UplinkVideoStats("none", null, null, null), UplinkVideoStats.from(emptyMap()))
    }

    @Test
    fun targetToneSeparatesLiveReadyAttentionAndEnded() {
        listOf(
            Triple("streaming", "live", BroadcastTargetTone.LIVE),
            Triple("idle", "prepared", BroadcastTargetTone.READY),
            Triple("idle", "preparing", BroadcastTargetTone.PROGRESS),
            Triple("streaming", "going_live", BroadcastTargetTone.PROGRESS),
            Triple("reconfiguring", "live", BroadcastTargetTone.PROGRESS),
            Triple("reconnecting", "live", BroadcastTargetTone.ATTENTION),
            Triple("paused", "live", BroadcastTargetTone.ATTENTION),
            Triple("paused_reconnecting", "live", BroadcastTargetTone.ATTENTION),
            Triple("stopped", "live", BroadcastTargetTone.ENDED),
            Triple("future_status", "future_phase", BroadcastTargetTone.PROGRESS),
        ).forEach { (status, phase, tone) ->
            assertEquals("$status/$phase", tone, target(status, phase).tone)
        }
    }

    @Test
    fun targetLabelDescribesOnlyTheState() {
        assertEquals(R.string.live_status_target_streaming, target("streaming", "live").statusLabelRes)
        assertEquals(R.string.live_status_target_reconnecting, target("reconnecting", "live").statusLabelRes)
        assertEquals(R.string.live_status_target_prepared, target("idle", "prepared").statusLabelRes)
        assertEquals(R.string.live_status_target_stopped, target("stopped", "live").statusLabelRes)
        assertEquals(R.string.live_status_target_checking, target(null, null).statusLabelRes)
    }

    private fun sample(
        reason: String = "none",
        width: Int = 1280,
        height: Int = 720,
        fps: Double? = 30.0,
    ) = UplinkVideoStats(reason, width, height, fps)

    private fun target(status: String?, phase: String?) = SessionTarget(
        provider = "youtube",
        status = status,
        broadcastPhase = phase,
        stopReason = null,
        reconnectAttempts = null,
    )
}
