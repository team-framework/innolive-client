package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioTimelineTest {
    @Test fun timestampsFollowSampleCountNotJitteryArrival() {
        val timeline = RecordingAudioTimeline(48_000)
        assertEquals(1_000_000L, timeline.next(1_000_000L, 480))
        // 도착이 3ms 늦어도 시간축은 샘플 수(10ms)로 이어진다.
        assertEquals(1_010_000L, timeline.next(1_013_000L, 480))
        assertEquals(1_020_000L, timeline.next(1_019_000L, 480))
    }

    @Test fun longGapRealignsToArrivalInsteadOfCatchingUp() {
        val timeline = RecordingAudioTimeline(48_000)
        timeline.next(0L, 480)
        assertEquals(10_000L, timeline.next(10_000L, 480))
        // 500ms 공백은 실제 캡처 중단이므로 도착 시각으로 다시 맞춘다.
        assertEquals(520_000L, timeline.next(520_000L, 480))
        assertEquals(530_000L, timeline.next(531_000L, 480))
    }

    @Test fun resyncNeverMovesBackwards() {
        val timeline = RecordingAudioTimeline(48_000)
        timeline.next(1_000_000L, 48_000)
        // 1초치 샘플을 받았는데 도착이 그보다 훨씬 이르면(시계 역행) 기존 시간축 끝을 유지한다.
        assertEquals(2_000_000L, timeline.next(1_200_000L, 480))
    }
}

class RecordingAudioClockTest {
    @Test fun contiguousChunksNeedNoSilence() {
        val clock = RecordingAudioClock()
        assertEquals(RecordingAudioClock.Placement(0, 0, 0, 0), clock.place(1_000_000L, 480))
        // 1ms 흔들림은 허용 범위(3ms) 안이라 무음을 넣지 않는다.
        assertEquals(RecordingAudioClock.Placement(480, 0, 0, 480), clock.place(1_011_000L, 480))
        assertEquals(1_020_000L, clock.timeOfFrame(960))
    }

    @Test fun captureGapIsFilledWithSilenceInsteadOfSkippingTheClock() {
        val clock = RecordingAudioClock()
        clock.place(0L, 480)
        clock.place(10_000L, 480)
        // 500ms 끊김: 기존 구현은 시계만 건너뛰었지만 이제 그 길이만큼 실제 무음을 쓴다.
        val placement = clock.place(520_000L, 480)
        assertEquals(960L, placement.silenceStartFrame)
        assertEquals(24_000L, placement.silenceFrames)
        assertEquals(24_960L, placement.chunkStartFrame)
        assertEquals(520_000L, clock.timeOfFrame(placement.chunkStartFrame))
        assertEquals(25_440L, clock.framesWritten)
    }

    @Test fun earlyChunkDoesNotRewindTheWrittenTimeline() {
        val clock = RecordingAudioClock(resyncThresholdUs = 1_000L)
        clock.place(0L, 4_800)
        // 도착이 이미 쓴 끝(100ms)보다 이르면 시간축은 뒤로 가지 않고 이어 쓴다.
        val placement = clock.place(50_000L, 4_800)
        assertEquals(0L, placement.silenceFrames)
        assertEquals(0, placement.skipFrames)
    }
}

class RecordingAudioConverterTest {
    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
    }

    @Test fun monoAt48kIsDuplicatedToStereo() {
        val converter = RecordingAudioConverter()
        val output = converter.convert(pcm(100, -200, 300), 6, 48_000, 1)
        assertEquals(listOf<Short>(100, 100, -200, -200, 300, 300), output.toList())
        assertEquals(true, converter.formatChanged)
        converter.convert(pcm(1), 2, 48_000, 1)
        assertEquals(false, converter.formatChanged)
    }

    @Test fun otherRatesAreResampledAcrossChunkBoundaries() {
        val converter = RecordingAudioConverter()
        var produced = 0
        // 16kHz 1초를 10ms씩 넣으면 48kHz 1초(48,000프레임) 가까이 나온다.
        repeat(100) { produced += converter.convert(pcm(*IntArray(160) { 1_000 }), 320, 16_000, 1).size / 2 }
        assertEquals(48_000.0, produced.toDouble(), 3.0)
        val stereo = converter.convert(pcm(*IntArray(320) { if (it % 2 == 0) 500 else -500 }), 640, 44_100, 2)
        assertTrue(stereo.isNotEmpty())
        assertEquals(true, converter.formatChanged)
        assertEquals(listOf<Short>(500, -500), stereo.toList().takeLast(2))
    }

    @Test fun floatS16IsClampedToPcm16() {
        val floats = java.nio.FloatBuffer.wrap(floatArrayOf(0f, 32_767f, -32_768f, 40_000f, Float.NaN, -1.5f))
        val bytes = ByteArray(12)
        floatS16ToPcm16(floats, 6, bytes)
        val values = (0 until 6).map { ((bytes[it * 2].toInt() and 0xff) or (bytes[it * 2 + 1].toInt() shl 8)).toShort().toInt() }
        assertEquals(listOf(0, 32_767, -32_768, 32_767, 0, -1), values)
    }

    @Test fun webRtcTapCopiesPostProcessingSignalAsMonoAtTheProcessingRate() {
        val received = mutableListOf<Triple<Int, Int, Long>>()
        val tap = WebRtcRecordingAudioTap(nowUs = { 1_000_000L })
        val buffer = java.nio.ByteBuffer.allocateDirect(480 * 4).order(java.nio.ByteOrder.nativeOrder())
        repeat(480) { buffer.putFloat(it.toFloat()) }
        buffer.flip()
        tap.process(3, 480, buffer)
        assertTrue(received.isEmpty())
        tap.sink = RecordingAudioSink { data, size, rate, channels, timeUs ->
            assertEquals(479, (data[958].toInt() and 0xff) or (data[959].toInt() shl 8))
            received += Triple(size, rate * 10 + channels, timeUs)
        }
        tap.process(3, 480, buffer)
        // 10ms 청크: 48kHz 모노 960바이트, 첫 샘플 시각은 도착 시각에서 10ms를 뺀 값이다.
        assertEquals(listOf(Triple(960, 480_001, 990_000L)), received)
        // 송출 버퍼는 읽기만 한다.
        assertEquals(479f, buffer.getFloat(479 * 4))
    }
}
