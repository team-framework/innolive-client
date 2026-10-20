package com.framework.innolive.feature.live.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.WebRtcNativeRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * 실제 MediaCodec·EGL로 녹화 세션을 돌려 조각 MP4 저장·마무리·복구·분리 내보내기 파일을 플랫폼 파서로
 * 확인합니다. 실제 기기에서만 실행합니다.
 */
class BroadcastRecordingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun record(directory: File, seconds: Int): BroadcastRecordingSession {
        WebRtcNativeRuntime.initialize(context)
        val egl = EglBase.create()
        val session = BroadcastRecordingSession(
            id = directory.name, directory = directory, sessionId = "device", provider = "youtube",
            createdAtMillis = System.currentTimeMillis(), media = AndroidRecordingMediaFactory(egl.eglBaseContext),
            alignProcessed = false, onFailure = { throw AssertionError("녹화 실패: $it") },
        )
        val pcm = ByteArray(960)
        val frames = seconds * 30
        for (index in 0 until frames) {
            val buffer = JavaI420Buffer.allocate(1280, 720)
            repeat(1280 * 720) { buffer.dataY.put(it, ((it + index * 7) % 255).toByte()) }
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            session.rawSink.onFrame(frame)
            session.processedSink.onFrame(frame)
            frame.release()
            repeat(3) { session.audioSink.onPcm(pcm, pcm.size, 48_000, 1, System.nanoTime() / 1_000L - 10_000L) }
            Thread.sleep(33)
        }
        return session
    }

    private fun trackCount(file: File, prefix: String): Pair<Int, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)
            }
            if (tracks.isEmpty()) return 0 to 0L
            extractor.selectTrack(tracks.single())
            var samples = 0L
            while (extractor.sampleTrackIndex >= 0) { samples++; extractor.advance() }
            return tracks.size to samples
        } finally {
            extractor.release()
        }
    }

    @Test fun savedRecordingIsAPlainMp4WithBothTracks() {
        val directory = File(context.cacheDir, "recording-device-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // 프레임 시각이 실제 시계라 기기 속도에 따라 길이가 늘어납니다. 기록에 걸린 시간과 비교합니다.
            val startedAt = SystemClock.elapsedRealtime()
            val session = record(directory, seconds = 4)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val saved = session.stop()
            val original = File(directory, "original.mp4")
            assertNotNull(BroadcastRecordingSession.validateRecordingFile(original))
            assertTrue("duration=${saved.durationMs} elapsed=$elapsedMs",
                saved.durationMs in maxOf(3_000L, elapsedMs - 1_000L)..elapsedMs + 500L)
            assertEquals(1, trackCount(original, "video/").first)
            assertEquals(1, trackCount(original, "audio/").first)
            assertTrue(saved.hasProcessed)

            val video = File(directory, "video.mp4")
            val audio = File(directory, "audio.m4a")
            Mp4TrackExtractor.extract(original, video, audio = false)
            Mp4TrackExtractor.extract(original, audio, audio = true)
            assertEquals(1 to trackCount(original, "video/").second, trackCount(video, "video/"))
            assertEquals(0, trackCount(video, "audio/").first)
            assertEquals(1 to trackCount(original, "audio/").second, trackCount(audio, "audio/"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun recordingCutMidFragmentIsRecoveredAndPlayable() {
        val root = File(context.cacheDir, "recording-device-root-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            val session = record(directory, seconds = 4)
            session.markConfirmed()
            // 프로세스 종료를 흉내 냅니다: 인코더를 멈추고 마지막 조각 일부를 잘라 냅니다.
            session.abandon(confirmed = true)
            RecordingJournal.write(directory, checkNotNull(RecordingJournal.read(directory)).copy(status = RecordingJournal.Status.CONFIRMED))
            val partial = File(directory, "original.partial.mp4")
            RandomAccessFile(partial, "rw").use { it.setLength(it.length() - 1_000) }

            val library = BroadcastRecordingStore(root).load()
            val recovered = library.recordings.single()
            assertTrue(recovered.recovered)
            assertNotNull(BroadcastRecordingSession.validateRecordingFile(File(directory, "original.mp4")))
        } finally {
            root.deleteRecursively()
        }
    }
}
