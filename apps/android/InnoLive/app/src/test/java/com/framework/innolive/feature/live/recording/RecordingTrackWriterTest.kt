package com.framework.innolive.feature.live.recording

import com.framework.innolive.feature.live.direct.publish.EncodedPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class RecordingTrackWriterTest {
    @get:Rule val folder = TemporaryFolder()

    private fun video(index: Int, key: Boolean, timeUs: Long = index * 33_333L, size: Int = 100) = EncodedPacket(
        ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + ByteArray(size) { 7 }), timeUs, key,
    )

    private fun audio(index: Int, timeUs: Long = index * 21_333L) = EncodedPacket(ByteBuffer.wrap(ByteArray(32)), timeUs, false)

    private val failures = mutableListOf<RecordingFailure>()

    private fun writer(maxBytes: Long = RecordingTrackWriter.DEFAULT_MAX_PENDING_BYTES) =
        RecordingTrackWriter(folder.newFile(), 0L, { reason, _ -> failures += reason }, maxPendingBytes = maxBytes)

    @Test fun packetsWaitForBothFormatsThenStartFromTheFirstKeyFrame() {
        val writer = writer()
        // 키프레임 앞의 영상과 형식 전에 온 출력은 보관해 두었다가 시작 때 쓴다.
        writer.writeVideo(video(0, key = false))
        writer.writeAudio(audio(0))
        writer.setVideoConfig(TEST_VIDEO)
        writer.writeVideo(video(1, key = true))
        writer.writeVideo(video(2, key = false))
        writer.writeAudio(audio(1))
        assertTrue(writer.pendingByteCount > 0)
        writer.setAudioConfig(TEST_AUDIO)
        assertEquals(0L, writer.pendingByteCount)
        writer.writeVideo(video(3, key = false))
        writer.writeAudio(audio(2))
        val result = writer.stop()
        assertTrue(result.started)
        assertEquals(3L, result.videoSamples)
        assertEquals(3L, result.audioSamples)
        // 첫 키프레임 앞의 영상 하나를 버렸다.
        assertEquals(1L, result.droppedSamples)
        assertTrue(failures.isEmpty())
    }

    @Test fun nonIncreasingTimestampsAreDropped() {
        val writer = writer()
        writer.setVideoConfig(TEST_VIDEO)
        writer.setAudioConfig(TEST_AUDIO)
        writer.writeVideo(video(0, key = true, timeUs = 0L))
        writer.writeVideo(video(1, key = false, timeUs = 33_333L))
        writer.writeVideo(video(2, key = false, timeUs = 33_333L))
        writer.writeVideo(video(3, key = false, timeUs = 20_000L))
        writer.writeVideo(video(4, key = false, timeUs = 66_666L))
        writer.writeAudio(audio(0, 0L))
        writer.writeAudio(audio(1, 0L))
        writer.writeAudio(audio(2, 21_333L))
        val result = writer.stop()
        assertEquals(3L, result.videoSamples)
        assertEquals(2L, result.audioSamples)
        assertEquals(3L, result.droppedSamples)
    }

    @Test fun missingAudioFormatFailsAsNoAudioWithinTheMemoryBound() {
        val writer = writer(maxBytes = 64 * 1024)
        writer.setVideoConfig(TEST_VIDEO)
        var index = 0
        while (failures.isEmpty() && index < 10_000) writer.writeVideo(video(index, index % 30 == 0, size = 4_000).also { index++ })
        assertEquals(listOf(RecordingFailure.NO_AUDIO), failures)
        // 한도를 넘긴 즉시 보관 출력을 버린다(이전 구현은 1,500개, 약 60MB까지 보관).
        assertTrue(index * 4_000 < 80 * 1024)
        assertEquals(0L, writer.pendingByteCount)
        val result = writer.stop()
        assertFalse(result.started)
        assertEquals(RecordingFailure.NO_AUDIO, result.failureReason)
    }

    @Test fun missingVideoFormatFailsAsNoVideoAfterTheDurationBound() {
        val writer = writer()
        writer.setAudioConfig(TEST_AUDIO)
        for (index in 0..300) writer.writeAudio(audio(index))
        assertEquals(listOf(RecordingFailure.NO_VIDEO), failures)
    }

    @Test fun abortDeletesTheFile() {
        val file = folder.newFile()
        val writer = RecordingTrackWriter(file, 0L, { _, _ -> })
        writer.setVideoConfig(TEST_VIDEO)
        writer.setAudioConfig(TEST_AUDIO)
        writer.writeVideo(video(0, key = true))
        writer.abort()
        assertFalse(file.exists())
        assertNull(writer.stop().failure)
    }
}
