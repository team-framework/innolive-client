package com.framework.innolive.feature.live.direct.publish

import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmClockTest {
    private val rate = 48_000
    private val chunkFrames = rate / 100 // 10ms

    @Test fun burstReadsGetContiguousTimes() {
        val clock = PcmClock(rate)
        // 20ms마다 두 청크가 몰려 오고 깨어나는 시각이 흔들립니다.
        val jitterUs = listOf(0L, 1_200L, 300L, 900L, 0L, 1_500L)
        val times = jitterUs.flatMapIndexed { period, jitter ->
            val wakeUs = 1_000_000L + period * 20_000L + jitter
            listOf(clock.next(chunkFrames, wakeUs), clock.next(chunkFrames, wakeUs + 50))
        }
        assertEquals(List(times.size) { 990_000L + it * 10_000L }, times)
    }

    @Test fun resyncsWhenSamplesWereLost() {
        val clock = PcmClock(rate)
        assertEquals(990_000L, clock.next(chunkFrames, 1_000_000L))
        assertEquals(1_000_000L, clock.next(chunkFrames, 1_010_000L))
        // 200ms 동안 샘플을 받지 못했습니다.
        assertEquals(1_200_000L, clock.next(chunkFrames, 1_210_000L))
        assertEquals(1_210_000L, clock.next(chunkFrames, 1_210_100L))
    }

    @Test fun resyncsWhenTimelineRunsAhead() {
        val clock = PcmClock(rate, resyncUs = 100_000L)
        assertEquals(990_000L, clock.next(chunkFrames, 1_000_000L))
        // 샘플 시각이 읽은 시각보다 100ms 넘게 앞서면 읽은 시각으로 돌아옵니다.
        repeat(10) { clock.next(chunkFrames, 1_000_100L) }
        assertEquals(990_100L, clock.next(chunkFrames, 1_000_100L))
    }

    @Test fun directAudioInputKeepsEveryBurstChunk() {
        val profile = DirectPublishProfile.of(DirectPublishResolution.HD_720, PublishOrientation.LANDSCAPE)
        val frames = profile.audioSampleRate / 100
        val bytes = frames * 2 * profile.audioChannels
        var nowUs = 1_000_000L
        lateinit var mic: PcmSink
        val received = mutableListOf<Long>()
        val input = DirectAudioInput(profile, { _, _, _ ->
            object : PcmSource {
                override fun start(sink: PcmSink) { mic = sink }
                override fun close() = Unit
            }
        }, null, {}, nowUs = { nowUs }, keeperFactory = null)
        input.start { _, _, time -> received += time }
        val clock = PcmClock(profile.audioSampleRate)
        val jitterUs = listOf(800L, 0L, 1_500L, 200L, 0L, 1_100L, 400L, 0L)
        jitterUs.forEachIndexed { period, jitter ->
            nowUs = 1_020_000L + period * 20_000L + jitter
            mic.onPcm(ByteArray(bytes), bytes, clock.next(frames, nowUs))
            nowUs += 50
            mic.onPcm(ByteArray(bytes), bytes, clock.next(frames, nowUs))
        }
        assertEquals(jitterUs.size * 2, received.size)
        assertEquals(List(received.size) { 10_000L }, (listOf(received.first() - 10_000L) + received).zipWithNext { a, b -> b - a })
    }
}
