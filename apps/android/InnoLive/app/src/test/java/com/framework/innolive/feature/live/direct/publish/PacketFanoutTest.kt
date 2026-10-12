package com.framework.innolive.feature.live.direct.publish

import com.framework.innolive.feature.live.BroadcastProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PacketFanoutTest {
    private class FakeEndpoint(override var isPublishing: Boolean = true) : PublishEndpoint {
        val events = mutableListOf<String>()

        override fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer) {
            events += "config ${sps.remaining()}/${pps.remaining()}"
        }

        override fun sendVideo(data: ByteBuffer, presentationTimeUs: Long, isKeyFrame: Boolean) {
            events += "video $presentationTimeUs ${if (isKeyFrame) "key" else "delta"} ${data.remaining()}"
        }

        override fun sendAudio(data: ByteBuffer, presentationTimeUs: Long) {
            events += "audio $presentationTimeUs"
        }
    }

    private var keyFrameRequests = 0
    private val fanout = PacketFanout { keyFrameRequests++ }

    private fun video(us: Long, key: Boolean = false) = EncodedPacket(ByteBuffer.wrap(ByteArray(10)), us, key)
    private fun audio(us: Long) = EncodedPacket(ByteBuffer.wrap(ByteArray(4)), us, false)
    private fun configure() = fanout.onVideoConfig(ByteBuffer.wrap(ByteArray(8)), ByteBuffer.wrap(ByteArray(4)))

    @Test fun targetStartsAtKeyFrameWithZeroTimestampAndConfig() {
        val youtube = FakeEndpoint()
        fanout.add(BroadcastProvider.YOUTUBE, youtube)
        configure()
        fanout.onAudio(audio(900))
        fanout.onVideo(video(1_000))
        fanout.onVideo(video(2_000, key = true))
        fanout.onAudio(audio(1_500))
        fanout.onAudio(audio(2_100))
        fanout.onVideo(video(3_000))
        assertEquals(
            listOf("config 8/4", "video 0 key 10", "audio 100", "video 1000 delta 10"),
            youtube.events,
        )
        assertEquals(1, keyFrameRequests)
    }

    @Test fun lateTargetGetsItsOwnTimelineWithoutDisturbingFirst() {
        val youtube = FakeEndpoint()
        val chzzk = FakeEndpoint()
        fanout.add(BroadcastProvider.YOUTUBE, youtube)
        configure()
        fanout.onVideo(video(1_000, key = true))
        fanout.add(BroadcastProvider.CHZZK, chzzk)
        fanout.onVideo(video(2_000))
        fanout.onVideo(video(5_000, key = true))
        assertEquals(listOf("config 8/4", "video 0 key 10", "video 1000 delta 10", "video 4000 key 10"), youtube.events)
        assertEquals(listOf("config 8/4", "video 0 key 10"), chzzk.events)
        assertEquals(1, keyFrameRequests)
    }

    @Test fun reconnectedTargetWaitsForNextKeyFrameAndRestartsAtZero() {
        val youtube = FakeEndpoint()
        fanout.add(BroadcastProvider.YOUTUBE, youtube)
        configure()
        fanout.onVideo(video(1_000, key = true))
        youtube.isPublishing = false
        fanout.onVideo(video(2_000))
        youtube.isPublishing = true
        fanout.onVideo(video(3_000))
        fanout.onVideo(video(4_000, key = true))
        assertEquals(listOf("config 8/4", "video 0 key 10", "config 8/4", "video 0 key 10"), youtube.events)
        assertEquals(1, keyFrameRequests)
    }

    @Test fun notPublishingTargetReceivesNothing() {
        val chzzk = FakeEndpoint(isPublishing = false)
        fanout.add(BroadcastProvider.CHZZK, chzzk)
        configure()
        fanout.onVideo(video(1_000, key = true))
        fanout.onAudio(audio(1_100))
        assertTrue(chzzk.events.isEmpty())
        assertEquals(0, keyFrameRequests)
    }

    @Test fun keyFrameWithoutCodecConfigIsHeldBack() {
        val youtube = FakeEndpoint()
        fanout.add(BroadcastProvider.YOUTUBE, youtube)
        fanout.onVideo(video(1_000, key = true))
        assertTrue(youtube.events.isEmpty())
        configure()
        fanout.onVideo(video(2_000, key = true))
        assertEquals(listOf("config 8/4", "video 0 key 10"), youtube.events)
    }

    @Test fun newCodecConfigRestartsEveryTarget() {
        val youtube = FakeEndpoint()
        fanout.add(BroadcastProvider.YOUTUBE, youtube)
        configure()
        fanout.onVideo(video(1_000, key = true))
        configure()
        fanout.onVideo(video(2_000))
        fanout.onVideo(video(3_000, key = true))
        assertEquals(listOf("config 8/4", "video 0 key 10", "config 8/4", "video 0 key 10"), youtube.events)
    }

    @Test fun removedTargetStopsReceiving() {
        val youtube = FakeEndpoint()
        fanout.add(BroadcastProvider.YOUTUBE, youtube)
        configure()
        fanout.onVideo(video(1_000, key = true))
        fanout.remove(BroadcastProvider.YOUTUBE)
        fanout.onVideo(video(2_000))
        assertEquals(listOf("config 8/4", "video 0 key 10"), youtube.events)
        assertTrue(fanout.providers().isEmpty())
    }

    @Test fun sendsDoNotMoveSharedEncoderBuffer() {
        val youtube = FakeEndpoint()
        val chzzk = FakeEndpoint()
        fanout.add(BroadcastProvider.YOUTUBE, youtube)
        fanout.add(BroadcastProvider.CHZZK, chzzk)
        configure()
        val packet = video(1_000, key = true)
        fanout.onVideo(packet)
        assertEquals(10, packet.data.remaining())
        assertEquals(youtube.events, chzzk.events)
    }
}

class KeyFrameSchedulerTest {
    @Test fun requestsBeforeIntervalAndRetriesUntilKeyFrameArrives() {
        val scheduler = KeyFrameScheduler(intervalUs = 2_000_000, leadUs = 100_000, retryUs = 500_000)
        assertEquals(false, scheduler.onVideoPacket(0, isKeyFrame = false))
        assertEquals(false, scheduler.onVideoPacket(33_000, isKeyFrame = true))
        assertEquals(false, scheduler.onVideoPacket(1_899_999, isKeyFrame = false))
        assertEquals(true, scheduler.onVideoPacket(1_933_000, isKeyFrame = false))
        assertEquals(false, scheduler.onVideoPacket(2_000_000, isKeyFrame = false))
        assertEquals(true, scheduler.onVideoPacket(2_433_000, isKeyFrame = false))
        assertEquals(false, scheduler.onVideoPacket(2_466_000, isKeyFrame = true))
        assertEquals(false, scheduler.onVideoPacket(4_000_000, isKeyFrame = false))
        assertEquals(true, scheduler.onVideoPacket(4_366_000, isKeyFrame = false))
    }
}
