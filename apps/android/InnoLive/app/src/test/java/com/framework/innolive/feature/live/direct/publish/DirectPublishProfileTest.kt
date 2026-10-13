package com.framework.innolive.feature.live.direct.publish

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectPublishProfileTest {
    @Test fun resolutionMatchesSessionResolutionAndOrientation() {
        val hd = DirectPublishProfile.of(DirectPublishResolution.HD_720, PublishOrientation.LANDSCAPE)
        assertEquals(1280 to 720, hd.width to hd.height)
        assertEquals(4_000_000, hd.videoBitrateBps)
        val fhdPortrait = DirectPublishProfile.of(DirectPublishResolution.FHD, PublishOrientation.PORTRAIT)
        assertEquals(1080 to 1920, fhdPortrait.width to fhdPortrait.height)
        assertEquals(6_000_000, fhdPortrait.videoBitrateBps)
        for (profile in listOf(hd, fhdPortrait)) {
            assertEquals(30, profile.fps)
            assertEquals(2, profile.keyFrameIntervalSeconds)
            assertEquals(48_000, profile.audioSampleRate)
        }
    }

    @Test fun rejectsSettingsPlatformsDoNotAccept() {
        val base = DirectPublishProfile.of(DirectPublishResolution.HD_720, PublishOrientation.LANDSCAPE)
        assertThrows(IllegalArgumentException::class.java) { base.copy(width = 1279) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(keyFrameIntervalSeconds = 5) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(audioChannels = 3) }
    }

    @Test fun viewportKeepsAspectRatio() {
        assertEquals(FitViewport(0, 0, 1280, 720), fitViewport(1920, 1080, 1280, 720))
        assertEquals(FitViewport(0, 0, 1080, 1920), fitViewport(720, 1280, 1080, 1920))
        assertEquals(FitViewport(160, 0, 960, 720), fitViewport(1440, 1080, 1280, 720))
        assertEquals(FitViewport(0, 420, 1080, 1080), fitViewport(1000, 1000, 1080, 1920))
        assertEquals(FitViewport(0, 0, 1280, 720), fitViewport(3840, 2160, 1280, 720))
    }

    @Test fun cameraTimestampsMoveToMonotonicClock() {
        val mono = 5_000_000_000L
        val realtime = 9_000_000_000L
        assertEquals(4_990_000_000L, cameraTimestampToMonotonicNs(4_990_000_000L, mono, realtime))
        assertEquals(4_970_000_000L, cameraTimestampToMonotonicNs(8_970_000_000L, mono, realtime))
        assertEquals(mono, cameraTimestampToMonotonicNs(123L, mono, realtime))
        assertEquals(mono, cameraTimestampToMonotonicNs(mono + 1, mono, realtime))
    }

    @Test fun frameRateIsAveragedSinceLastSample() {
        var now = 0L
        val meter = FrameRateMeter { now }
        assertEquals(0.0, meter.sample(), 0.0)
        repeat(30) { meter.onFrame() }
        now = 1_000_000_000L
        assertEquals(30.0, meter.sample(), 0.001)
        repeat(15) { meter.onFrame() }
        now = 2_000_000_000L
        assertEquals(15.0, meter.sample(), 0.001)
        now = 3_000_000_000L
        assertEquals(0.0, meter.sample(), 0.001)
    }

    @Test fun statsMapToHeartbeatFields() {
        val stats = DirectPublishStats(
            targets = listOf(TargetPublishStats(BroadcastProvider.CHZZK, true, 5100, 1)),
            fps = 29.8, droppedFrames = 12, width = 1280, height = 720,
        )
        assertEquals(1, stats.heartbeatTargets().single().reconnects)
        assertEquals(5100, stats.heartbeatTargets().single().bitrateKbps)
        assertEquals(12L, stats.heartbeatVideo().droppedFrames)
        assertEquals(1280, stats.heartbeatVideo().width)
    }

    @Test fun rtmpFailureReasonsAreClassifiedWithoutKeepingText() {
        assertEquals(RtmpFailure.REJECTED, classifyRtmpFailure("onStatus: NetStream.Publish.BadName"))
        assertEquals(RtmpFailure.REJECTED, classifyRtmpFailure("Endpoint malformed, should be: rtmp://ip:port/appname/streamname"))
        assertEquals(RtmpFailure.NETWORK, classifyRtmpFailure("Error configure stream, timeout"))
    }
}
