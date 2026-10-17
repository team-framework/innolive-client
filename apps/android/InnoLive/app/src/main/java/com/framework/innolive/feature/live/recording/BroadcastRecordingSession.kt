package com.framework.innolive.feature.live.recording

import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import com.framework.innolive.feature.live.direct.publish.AacEncoder
import com.framework.innolive.feature.live.direct.publish.EncodedPacket
import com.framework.innolive.feature.live.direct.publish.EncoderOutput
import com.framework.innolive.feature.live.direct.publish.EncoderSurfaceRenderer
import com.framework.innolive.feature.live.direct.publish.cameraTimestampToMonotonicNs
import org.webrtc.EglBase
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class RecordingFailure { NO_VIDEO, NO_AUDIO, ENCODER, STORAGE, INVALID_FILE, START }

internal class RecordingFailedException(val reason: RecordingFailure, cause: Throwable? = null) :
    Exception(reason.name, cause)

/**
 * 한 방송의 녹화입니다. 카메라 원본(블러 전)과 실제 송출 프레임(블러 후)을 각각 H.264로, 송출 음성을
 * AAC로 한 번 인코딩해 두 MP4(`original`, `processed`)에 같은 음성 트랙으로 기록합니다.
 *
 * 프레임·PCM 싱크는 캡처 스레드에서 오며 녹화 오류는 콜백으로만 알리고 방송을 멈추지 않습니다.
 * [stop]은 싱크를 모두 끊은 뒤 작업 스레드에서 부르며 인코더 종료를 기다립니다.
 */
internal class BroadcastRecordingSession(
    val id: String,
    val directory: File,
    private val sharedEglContext: EglBase.Context,
    private val sessionId: String?,
    private val provider: String,
    private val createdAtMillis: Long,
    private val onFailure: (RecordingFailure) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
    private val realtimeNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val stopped = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)
    private val originalPartial = File(directory, "$ORIGINAL_FILE_NAME.partial.mp4")
    private val processedPartial = File(directory, "$PROCESSED_FILE_NAME.partial.mp4")
    private val originalWriter = RecordingTrackWriter(originalPartial) { report(RecordingFailure.STORAGE, it) }
    // 송출 프레임 파일은 선택 사항입니다. 실패해도 원본 저장은 이어 갑니다.
    private val processedWriter = RecordingTrackWriter(processedPartial) { warn("processed_writer_failed", it) }
    private val original = VideoStream("original", originalWriter, fatal = true)
    private val processed = VideoStream("processed", processedWriter, fatal = false)
    private val audio = AudioStream()

    /** 비식별화·색 보정 전 카메라 프레임입니다. */
    val rawSink: VideoSink = VideoSink(original::onFrame)

    /** 실제 송출 프레임입니다. 서버 AI 모드는 서버가 돌려주는 영상, 온디바이스 모드는 가림 처리 출력입니다. */
    val processedSink: VideoSink = VideoSink(processed::onFrame)

    val audioSink: RecordingAudioSink = RecordingAudioSink(audio::onPcm)

    init {
        check(directory.isDirectory || directory.mkdirs()) { "녹화 폴더를 만들지 못했습니다." }
    }

    /**
     * 싱크를 끊은 뒤 작업 스레드에서 부릅니다. 인코더를 마무리하고 파일을 검증해 메타데이터를 남깁니다.
     * 원본 파일을 만들지 못하면 [RecordingFailedException]을 던지고 폴더를 지웁니다.
     */
    fun stop(): SavedBroadcastRecording {
        check(stopped.compareAndSet(false, true)) { "이미 종료한 녹화입니다." }
        original.finish()
        processed.finish()
        audio.finish()
        val originalResult = originalWriter.stop()
        val processedResult = processedWriter.stop()
        try {
            if (!originalResult.started || originalResult.videoSamples == 0L || originalResult.audioSamples == 0L) {
                throw RecordingFailedException(
                    when {
                        !audio.formatSeen -> RecordingFailure.NO_AUDIO
                        !original.formatSeen || originalResult.videoSamples == 0L -> RecordingFailure.NO_VIDEO
                        originalResult.failure != null -> RecordingFailure.STORAGE
                        else -> RecordingFailure.NO_AUDIO
                    },
                    originalResult.failure,
                )
            }
            val originalFile = File(directory, "$ORIGINAL_FILE_NAME.mp4")
            val durationMs = validateRecordingFile(originalPartial)
                ?: throw RecordingFailedException(RecordingFailure.INVALID_FILE)
            check(originalPartial.renameTo(originalFile)) { "원본 파일 이름을 바꾸지 못했습니다." }
            val processedFile = File(directory, "$PROCESSED_FILE_NAME.mp4")
            val hasProcessed = processedResult.started && processedResult.videoSamples > 0 &&
                validateRecordingFile(processedPartial) != null && processedPartial.renameTo(processedFile)
            if (!hasProcessed) processedPartial.delete()
            val recording = SavedBroadcastRecording(
                id = id,
                createdAtMillis = createdAtMillis,
                durationMs = durationMs,
                sessionId = sessionId,
                provider = provider,
                hasProcessed = hasProcessed,
                bytes = originalFile.length() + (if (hasProcessed) processedFile.length() else 0L),
                originalFrames = originalResult.videoSamples,
                processedFrames = if (hasProcessed) processedResult.videoSamples else 0L,
                droppedFrames = original.dropped.get() + processed.dropped.get(),
            )
            BroadcastRecordingStore.writeMetadata(directory, recording)
            return recording
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error as? RecordingFailedException ?: RecordingFailedException(RecordingFailure.STORAGE, error)
        }
    }

    /** 방송 시작을 확인하지 못한 녹화를 버립니다. 인코더 마무리를 기다리지 않습니다. */
    fun discard() {
        if (!stopped.compareAndSet(false, true)) return
        original.abandon()
        processed.abandon()
        audio.abandon()
        originalWriter.abort()
        processedWriter.abort()
        directory.deleteRecursively()
    }

    private fun report(reason: RecordingFailure, error: Exception?) {
        warn("recording_failed reason=$reason", error)
        if (!stopped.get() && reported.compareAndSet(false, true)) onFailure(reason)
    }

    private fun warn(message: String, error: Exception?) {
        Log.w(LOG_TAG, "$message type=${error?.javaClass?.simpleName}")
    }

    /** 영상 하나의 인코더·렌더러입니다. 첫 프레임 크기로 인코더를 만들고 이후 프레임은 비율을 유지해 맞춥니다. */
    private inner class VideoStream(
        private val name: String,
        private val writer: RecordingTrackWriter,
        private val fatal: Boolean,
    ) {
        private val lock = Any()
        private var encoder: RecordingVideoEncoder? = null
        private var renderer: EncoderSurfaceRenderer? = null
        private var closed = false
        private var failed = false
        private val endOfStream = CountDownLatch(1)
        private val lastPtsNs = AtomicLong(Long.MIN_VALUE)
        val dropped = AtomicLong()
        @Volatile var formatSeen = false
            private set

        fun onFrame(frame: VideoFrame) {
            val target = synchronized(lock) {
                if (closed || failed) return
                renderer ?: start(frame.rotatedWidth, frame.rotatedHeight)
            } ?: return
            val pts = cameraTimestampToMonotonicNs(frame.timestampNs, nowNs(), realtimeNs())
            val previous = lastPtsNs.get()
            if (pts <= previous || !lastPtsNs.compareAndSet(previous, pts)) {
                dropped.incrementAndGet()
                return
            }
            target.render(frame, pts)
        }

        /** 잠금 안에서 부릅니다. */
        private fun start(sourceWidth: Int, sourceHeight: Int): EncoderSurfaceRenderer? = try {
            val size = recordingSizeFor(sourceWidth, sourceHeight)
            val created = RecordingVideoEncoder(size.width, size.height, RECORDING_FPS, recordingVideoBitrateFor(size),
                object : EncoderOutput {
                    override fun onOutputFormat(format: MediaFormat) {
                        formatSeen = true
                        writer.setVideoFormat(format)
                    }

                    override fun onPacket(packet: EncodedPacket) = writer.writeVideo(packet)

                    override fun onEndOfStream() = endOfStream.countDown()

                    override fun onError(error: Exception) = fail(error)
                })
            encoder = created
            val surface = EncoderSurfaceRenderer(sharedEglContext, created.inputSurface, size.width, size.height,
                onRendered = {}, onDropped = { dropped.incrementAndGet() }, onError = ::fail)
            renderer = surface
            created.start()
            Log.i(LOG_TAG, "video_stream_started name=$name size=${size.width}x${size.height}")
            surface
        } catch (error: Exception) {
            fail(error)
            null
        }

        private fun fail(error: Exception) {
            synchronized(lock) {
                if (failed) return
                failed = true
            }
            endOfStream.countDown()
            if (fatal) report(RecordingFailure.ENCODER, error) else warn("${name}_stream_failed", error)
        }

        fun finish() {
            val (surface, codec) = synchronized(lock) {
                closed = true
                renderer to encoder
            }
            runCatching { surface?.close() }
            if (codec != null && !failed) {
                codec.signalEndOfInputStream()
                endOfStream.await(FINISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
            runCatching { codec?.close() }
        }

        fun abandon() {
            val (surface, codec) = synchronized(lock) {
                closed = true
                renderer to encoder
            }
            runCatching { surface?.close() }
            runCatching { codec?.close() }
        }
    }

    /** 송출 음성 하나의 AAC 인코더입니다. 첫 청크의 형식으로 만들고 두 파일에 같은 출력을 씁니다. */
    private inner class AudioStream {
        private val lock = Any()
        private var encoder: AacEncoder? = null
        private var sampleRate = 0
        private var channels = 0
        private var timeline: RecordingAudioTimeline? = null
        private var closed = false
        private var failed = false
        private val endOfStream = CountDownLatch(1)
        @Volatile var formatSeen = false
            private set

        fun onPcm(data: ByteArray, size: Int, rate: Int, channelCount: Int, presentationTimeUs: Long) {
            if (size <= 0 || rate <= 0 || channelCount !in 1..2) return
            val (codec, clock) = synchronized(lock) {
                if (closed || failed) return
                val current = encoder ?: start(rate, channelCount) ?: return
                // 송출 중 형식이 바뀌면(장치 교체) 그 청크는 버립니다. 무음 채움이 시간축을 이어 갑니다.
                if (rate != sampleRate || channelCount != channels) return
                current to checkNotNull(timeline)
            }
            val frames = size / (2 * channelCount)
            val pts = synchronized(clock) { clock.next(presentationTimeUs, frames) }
            codec.queuePcm(data, size, pts)
        }

        /** 잠금 안에서 부릅니다. */
        private fun start(rate: Int, channelCount: Int): AacEncoder? = try {
            sampleRate = rate
            channels = channelCount
            timeline = RecordingAudioTimeline(rate)
            AacEncoder(rate, channelCount, RECORDING_AUDIO_BITRATE_BPS, object : EncoderOutput {
                override fun onOutputFormat(format: MediaFormat) {
                    formatSeen = true
                    originalWriter.setAudioFormat(format)
                    processedWriter.setAudioFormat(format)
                }

                override fun onPacket(packet: EncodedPacket) {
                    originalWriter.writeAudio(EncodedPacket(packet.data.duplicate(), packet.presentationTimeUs, false))
                    processedWriter.writeAudio(EncodedPacket(packet.data.duplicate(), packet.presentationTimeUs, false))
                }

                override fun onEndOfStream() = endOfStream.countDown()

                override fun onError(error: Exception) = fail(error)
            }, name = "recording-audio").also {
                it.start()
                encoder = it
                Log.i(LOG_TAG, "audio_stream_started rate=$rate channels=$channelCount")
            }
        } catch (error: Exception) {
            fail(error)
            null
        }

        private fun fail(error: Exception) {
            synchronized(lock) {
                if (failed) return
                failed = true
            }
            endOfStream.countDown()
            report(RecordingFailure.ENCODER, error)
        }

        fun finish() {
            val codec = synchronized(lock) {
                closed = true
                encoder
            }
            if (codec != null && !failed) {
                codec.signalEndOfInput()
                endOfStream.await(FINISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
            runCatching { codec?.close() }
        }

        fun abandon() {
            val codec = synchronized(lock) {
                closed = true
                encoder
            }
            runCatching { codec?.close() }
        }
    }

    companion object {
        const val ORIGINAL_FILE_NAME = "original"
        const val PROCESSED_FILE_NAME = "processed"
        private const val LOG_TAG = "BroadcastRecording"
        private const val FINISH_TIMEOUT_MILLIS = 3_000L

        /** 영상·음성 트랙과 길이를 확인하고 길이(ms)를 돌려줍니다. 재생할 수 없으면 null입니다. */
        fun validateRecordingFile(file: File): Long? {
            if (!file.isFile || file.length() == 0L) return null
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                if (duration != null && duration > 0 && hasVideo && hasAudio) duration else null
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
