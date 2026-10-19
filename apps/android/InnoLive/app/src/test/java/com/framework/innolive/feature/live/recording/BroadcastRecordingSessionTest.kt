package com.framework.innolive.feature.live.recording

import com.framework.innolive.feature.live.direct.publish.EncodedPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.webrtc.VideoFrame
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BroadcastRecordingSessionTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeBuffer(private val width: Int, private val height: Int) : VideoFrame.Buffer {
        override fun getWidth() = width
        override fun getHeight() = height
        override fun toI420(): VideoFrame.I420Buffer? = null
        override fun retain() = Unit
        override fun release() = Unit
        override fun cropAndScale(x: Int, y: Int, w: Int, h: Int, sw: Int, sh: Int): VideoFrame.Buffer = FakeBuffer(sw, sh)
    }

    /** 그린 프레임마다 Annex-B 표본을 내보내는 가짜 인코더입니다. */
    private class FakeMedia(
        val failProcessed: Boolean = false,
        val videoEndOfStream: Boolean = true,
        val blockVideoSetup: CountDownLatch? = null,
    ) : RecordingMediaFactory {
        val created = mutableMapOf<String, CountDownLatch>()
        val ready = CountDownLatch(2)
        @Volatile var silenceBytes = 0
        @Volatile var pcmBytes = 0

        override fun createVideo(name: String, size: RecordingSize, bitrateBps: Int, output: RecordingVideoOutput): RecordingVideoPipeline {
            blockVideoSetup?.await(5, TimeUnit.SECONDS)
            if (failProcessed && name == "processed") throw IllegalStateException("인코더 세션 한도")
            var frames = 0
            output.onConfig(VideoTrackConfig(size.width, size.height, listOf(TEST_SPS), listOf(TEST_PPS)))
            ready.countDown()
            return object : RecordingVideoPipeline {
                override fun render(frame: VideoFrame, presentationTimeNs: Long) {
                    val key = frames++ % 30 == 0
                    output.onPacket(EncodedPacket(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41, 1, 2)),
                        presentationTimeNs / 1_000L, key))
                }
                override fun signalEndOfInput() { if (videoEndOfStream) output.onEndOfStream() }
                override fun close() = Unit
            }
        }

        override fun createAudio(output: RecordingAudioOutput): RecordingAudioEncoder {
            output.onConfig(TEST_AUDIO)
            ready.countDown()
            return object : RecordingAudioEncoder {
                override fun queuePcm(data: ByteArray, size: Int, presentationTimeUs: Long) {
                    pcmBytes += size
                    output.onPacket(EncodedPacket(ByteBuffer.wrap(ByteArray(16)), presentationTimeUs, false))
                }
                override fun queueSilence(size: Int, presentationTimeUs: Long) {
                    silenceBytes += size
                    output.onPacket(EncodedPacket(ByteBuffer.wrap(ByteArray(16)), presentationTimeUs, false))
                }
                override fun signalEndOfInput() = output.onEndOfStream()
                override fun close() = Unit
            }
        }
    }

    private var nowNs = 100_000_000_000L
    private val failures = mutableListOf<RecordingFailure>()

    private fun session(media: FakeMedia, directory: File = folder.newFolder()) = BroadcastRecordingSession(
        id = directory.name, directory = directory, sessionId = "s", provider = "youtube", createdAtMillis = 7L,
        media = media, alignProcessed = false, onFailure = { failures += it },
        nowNs = { nowNs }, realtimeNs = { nowNs }, validator = { 1_000L }, finishTimeoutMillis = 50L,
    )

    private fun frame() = VideoFrame(FakeBuffer(1280, 720), 0, nowNs)

    /** 영상(원본·송출)과 10ms 음성을 [seconds]초 동안 넣습니다. 첫 프레임 뒤 인코더 준비를 기다립니다. */
    private fun feed(session: BroadcastRecordingSession, media: FakeMedia, seconds: Int, processed: Boolean = true, audio: Boolean = true) {
        session.rawSink.onFrame(frame())
        if (processed) session.processedSink.onFrame(frame())
        if (audio) session.audioSink.onPcm(ByteArray(960), 960, 48_000, 1, nowNs / 1_000L)
        media.ready.await(2, TimeUnit.SECONDS)
        Thread.sleep(20)
        repeat(seconds * 100) { tick ->
            nowNs += 10_000_000L
            if (audio) session.audioSink.onPcm(ByteArray(960), 960, 48_000, 1, nowNs / 1_000L - 10_000L)
            if (tick % 3 == 0) {
                session.rawSink.onFrame(frame())
                if (processed) session.processedSink.onFrame(frame())
            }
        }
    }

    @Test fun stopSavesBothFilesEvenWhenTheEncoderNeverReportsEndOfStream() {
        val media = FakeMedia(videoEndOfStream = false)
        val session = session(media)
        session.markConfirmed()
        feed(session, media, seconds = 3)
        val started = System.nanoTime()
        val saved = session.stop()
        // 영상 인코더가 끝을 알리지 않아도 시간 제한 뒤 저장을 마친다.
        assertTrue((System.nanoTime() - started) / 1_000_000L < 2_000L)
        assertTrue(saved.hasProcessed)
        assertTrue(saved.originalFrames > 80)
        assertTrue(File(session.directory, "original.mp4").isFile)
        assertTrue(File(session.directory, "processed.mp4").isFile)
        assertFalse(File(session.directory, RecordingJournal.FILE_NAME).exists())
        assertEquals(saved, BroadcastRecordingStore.readMetadata(session.directory))
        assertTrue(failures.isEmpty())
    }

    @Test fun onlyTheProcessedFileFailsAndTheOriginalIsStillSaved() {
        val media = FakeMedia(failProcessed = true)
        media.ready.countDown()
        val session = session(media)
        feed(session, media, seconds = 2)
        val saved = session.stop()
        assertFalse(saved.hasProcessed)
        assertEquals(0L, saved.processedFrames)
        assertFalse(File(session.directory, "processed.mp4").exists())
        assertFalse(File(session.directory, "processed.partial.mp4").exists())
        assertTrue(failures.isEmpty())
    }

    @Test fun missingAudioFailsAsNoAudioAndRemovesTheRecording() {
        val media = FakeMedia()
        media.ready.countDown()
        val session = session(media)
        feed(session, media, seconds = 1, audio = false)
        try {
            session.stop()
            fail("음성이 없으면 저장하지 않는다")
        } catch (error: RecordingFailedException) {
            assertEquals(RecordingFailure.NO_AUDIO, error.reason)
        }
        assertFalse(session.directory.exists())
    }

    @Test fun encoderSetupRunsOffTheCaptureThread() {
        val gate = CountDownLatch(1)
        val media = FakeMedia(blockVideoSetup = gate)
        val session = session(media)
        val started = System.nanoTime()
        repeat(5) { session.rawSink.onFrame(frame()) }
        // MediaCodec·EGL 준비를 기다리지 않고 바로 돌아오며, 준비 전 프레임은 버린다.
        assertTrue((System.nanoTime() - started) / 1_000_000L < 500L)
        gate.countDown()
        session.discard()
        assertFalse(session.directory.exists())
    }

    @Test fun audioGapIsFilledWithRealSilence() {
        val media = FakeMedia()
        val session = session(media)
        feed(session, media, seconds = 1)
        // 500ms 동안 음성 콜백이 끊겼다.
        nowNs += 500_000_000L
        session.audioSink.onPcm(ByteArray(960), 960, 48_000, 1, nowNs / 1_000L)
        // 48kHz 스테레오 16비트로 약 500ms(96,000바이트)의 무음을 넣는다.
        assertTrue(media.silenceBytes in 90_000..100_000)
        session.discard()
    }

    @Test fun confirmedRecordingThatFailsMidBroadcastIsPreservedForTheLibrary() {
        val media = FakeMedia()
        val session = session(media)
        session.markConfirmed()
        feed(session, media, seconds = 2)
        session.abandon(confirmed = true)
        assertEquals(RecordingJournal.Status.FAILED, RecordingJournal.read(session.directory)?.status)
        assertTrue(File(session.directory, "original.partial.mp4").length() > 0)

        val unconfirmed = session(FakeMedia().also { it.ready.countDown(); it.ready.countDown() })
        unconfirmed.abandon(confirmed = false)
        assertFalse(unconfirmed.directory.exists())
    }
}
