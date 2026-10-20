package com.framework.innolive.feature.live.recording

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import com.framework.innolive.feature.live.direct.publish.EncodedPacket
import com.framework.innolive.feature.live.direct.publish.cameraTimestampToMonotonicNs
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal enum class RecordingFailure { NO_VIDEO, NO_AUDIO, ENCODER, STORAGE, INVALID_FILE, START }

internal class RecordingFailedException(val reason: RecordingFailure, cause: Throwable? = null) :
    Exception(reason.name, cause)

/**
 * 한 방송의 녹화입니다. 카메라 원본(블러 전)과 실제 송출 프레임(블러 후)을 각각 H.264로, 송출 음성을
 * 48kHz 스테레오 AAC로 한 번 인코딩해 두 조각 MP4(`original`, `processed`)에 같은 음성 트랙으로 기록합니다.
 *
 * - 인코더·EGL 준비는 첫 프레임을 받은 뒤 작업 스레드에서 합니다. 캡처·분석 스레드는 기다리지 않습니다.
 * - 음성 공백은 실제 무음으로 채웁니다. 서버 AI 모드([alignProcessed])는 돌려받은 영상의 왕복 지연을 재
 *   마무리할 때 영상 트랙 시작에서 뺍니다([RecordingLatencyEstimator]).
 * - 진행 상태는 [RecordingJournal]에 남겨 앱이 종료돼도 다음 실행에서 복구합니다.
 *
 * 프레임·PCM 싱크는 캡처 스레드에서 오며 녹화 오류는 콜백으로만 알리고 방송을 멈추지 않습니다.
 * [stop]은 싱크를 모두 끊은 뒤 작업 스레드에서 부르며 인코더 종료를 기다립니다.
 */
internal class BroadcastRecordingSession(
    val id: String,
    val directory: File,
    private val sessionId: String?,
    private val provider: String,
    private val createdAtMillis: Long,
    private val media: RecordingMediaFactory,
    private val alignProcessed: Boolean,
    private val onFailure: (RecordingFailure) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
    private val realtimeNs: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val validator: (File) -> Long? = { file -> validateRecordingFile(file) },
    private val finishTimeoutMillis: Long = FINISH_TIMEOUT_MILLIS,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "recording-worker") },
    openChannel: (File) -> FileChannel = FragmentedMp4Writer::openRecordingChannel,
) {
    private val stopped = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)
    private val epochUs = nowNs() / 1_000L - EPOCH_MARGIN_US
    private val originalPartial = File(directory, "$ORIGINAL_FILE_NAME.partial.mp4")
    private val processedPartial = File(directory, "$PROCESSED_FILE_NAME.partial.mp4")
    private val mp4Writer = { target: File, video: VideoTrackConfig, audio: AudioTrackConfig, epoch: Long ->
        FragmentedMp4Writer(target, video, audio, epoch, openChannel = openChannel)
    }
    private val originalWriter = RecordingTrackWriter(originalPartial, epochUs, { reason, error -> report(reason, error) },
        writerFactory = mp4Writer)
    // 송출 프레임 파일은 선택 사항입니다. 실패해도 원본 저장은 이어 갑니다.
    private val processedWriter = RecordingTrackWriter(processedPartial, epochUs, { reason, error ->
        RecordingLog.w("processed_writer_failed reason=$reason type=${error.javaClass.simpleName}")
    }, writerFactory = mp4Writer)
    private val latency = if (alignProcessed) RecordingLatencyEstimator() else null
    private val estimating = AtomicBoolean(false)
    private val processedProbes = AtomicInteger()
    private val original = VideoStream("original", originalWriter, fatal = true,
        probe = latency?.let { estimator -> { timeUs, grid -> estimator.addRaw(timeUs, grid) } })
    private val processed = VideoStream("processed", processedWriter, fatal = false,
        probe = latency?.let { estimator -> { timeUs, grid -> onProcessedProbe(estimator, timeUs, grid) } })
    private val audio = AudioStream()
    private val journalLock = Any()
    private var journal = RecordingJournal(id, sessionId, provider, createdAtMillis, RecordingJournal.Status.PROVISIONAL)

    /** 비식별화·색 보정 전 카메라 프레임입니다. */
    val rawSink: VideoSink = VideoSink(original::onFrame)

    /** 실제 송출 프레임입니다. 서버 AI 모드는 서버가 돌려주는 영상, 온디바이스 모드는 가림 처리 출력입니다. */
    val processedSink: VideoSink = VideoSink(processed::onFrame)

    val audioSink: RecordingAudioSink = RecordingAudioSink(audio::onPcm)

    /** 지금까지 잰 서버 반환 영상의 지연(µs)입니다. */
    val processedDelayUs: Long get() = latency?.offsetUs ?: 0L

    init {
        check(directory.isDirectory || directory.mkdirs()) { "녹화 폴더를 만들지 못했습니다." }
        RecordingJournal.write(directory, journal)
    }

    /** 방송 시작을 확인했습니다. 이 뒤로 앱이 종료되면 다음 실행에서 복구합니다. */
    fun markConfirmed() = updateJournal(RecordingJournal.Status.CONFIRMED)

    /** 저장을 시작합니다. */
    fun markFinalizing() = updateJournal(RecordingJournal.Status.FINALIZING)

    private fun updateJournal(status: RecordingJournal.Status? = null, delayUs: Long? = null) {
        synchronized(journalLock) {
            val next = journal.copy(status = status ?: journal.status, processedDelayUs = delayUs ?: journal.processedDelayUs)
            if (next == journal || !directory.isDirectory) return
            journal = next
            runCatching { RecordingJournal.write(directory, next) }
                .onFailure { RecordingLog.w("journal_write_failed type=${it.javaClass.simpleName}") }
        }
    }

    /**
     * 싱크를 끊은 뒤 작업 스레드에서 부릅니다. 인코더를 마무리하고 파일을 일반 MP4로 바꿔 검증한 뒤
     * 메타데이터를 남깁니다. 원본 파일을 만들지 못하면 [RecordingFailedException]을 던집니다.
     */
    fun stop(): SavedBroadcastRecording {
        check(stopped.compareAndSet(false, true)) { "이미 종료한 녹화입니다." }
        original.finish()
        processed.finish()
        audio.finish()
        worker.shutdown()
        val originalResult = originalWriter.stop()
        val processedResult = processedWriter.stop()
        val delayUs = processedDelayUs
        if (!originalResult.started || originalResult.videoSamples == 0L || originalResult.audioSamples == 0L) {
            val reason = originalResult.failureReason ?: when {
                !audio.formatSeen -> RecordingFailure.NO_AUDIO
                !original.formatSeen || originalResult.videoSamples == 0L -> RecordingFailure.NO_VIDEO
                originalResult.audioSamples == 0L -> RecordingFailure.NO_AUDIO
                else -> RecordingFailure.STORAGE
            }
            // 쓰기·마무리 오류로 끝나면 started가 false입니다. abandon처럼 이미 쓴 영상이 있으면 보관합니다.
            preserveOrDelete(keep = originalResult.videoSamples > 0L)
            throw RecordingFailedException(reason, originalResult.failure)
        }
        updateJournal(RecordingJournal.Status.WRITER_COMPLETED, delayUs)
        try {
            val finalized = Mp4Finalizer.finish(originalPartial, 0L)
            val durationMs = validator(originalPartial) ?: throw RecordingFailedException(RecordingFailure.INVALID_FILE)
            val originalFile = File(directory, "$ORIGINAL_FILE_NAME.mp4")
            check(originalPartial.renameTo(originalFile)) { "원본 파일 이름을 바꾸지 못했습니다." }
            val processedFile = File(directory, "$PROCESSED_FILE_NAME.mp4")
            var processedFrames = 0L
            val hasProcessed = processedResult.started && processedResult.videoSamples > 0 && processedResult.audioSamples > 0 &&
                runCatching {
                    processedFrames = Mp4Finalizer.finish(processedPartial, -delayUs).videoSamples.toLong()
                    validator(processedPartial) != null && processedPartial.renameTo(processedFile)
                }.onFailure { RecordingLog.w("processed_finalize_failed type=${it.javaClass.simpleName}") }.getOrDefault(false)
            if (!hasProcessed) processedPartial.delete()
            if (delayUs != 0L) RecordingLog.i("processed_alignment delay_ms=${delayUs / 1_000}")
            val recording = SavedBroadcastRecording(
                id = id,
                createdAtMillis = createdAtMillis,
                durationMs = durationMs,
                sessionId = sessionId,
                provider = provider,
                hasProcessed = hasProcessed,
                bytes = originalFile.length() + (if (hasProcessed) processedFile.length() else 0L),
                originalFrames = finalized.videoSamples.toLong(),
                processedFrames = if (hasProcessed) processedFrames else 0L,
                droppedFrames = original.dropped.get() + processed.dropped.get() +
                    originalResult.droppedSamples + processedResult.droppedSamples,
            )
            BroadcastRecordingStore.writeMetadata(directory, recording)
            RecordingJournal.delete(directory)
            return recording
        } catch (error: Exception) {
            // 인코더는 끝났으므로 기록을 남겨 다음에 보관함을 열 때 마무리를 다시 시도합니다(iOS writerCompleted).
            throw error as? RecordingFailedException ?: RecordingFailedException(RecordingFailure.STORAGE, error)
        }
    }

    /** 방송 시작을 확인하지 못한 녹화를 버립니다. 인코더 마무리를 기다리지 않습니다. */
    fun discard() {
        if (!stopped.compareAndSet(false, true)) return
        updateJournal(RecordingJournal.Status.ABORTED)
        abandonStreams()
        originalWriter.abort()
        processedWriter.abort()
        directory.deleteRecursively()
    }

    /**
     * 녹화 중 치명적인 오류로 끝냅니다. 확인된 방송이면 iOS처럼 다 쓴 조각을 실패 기록과 함께 보관하고,
     * 아니면 지웁니다. 보관한 파일은 자동 복구하지 않습니다.
     */
    fun abandon(confirmed: Boolean) {
        if (!stopped.compareAndSet(false, true)) return
        abandonStreams()
        val result = originalWriter.stop()
        processedWriter.stop()
        preserveOrDelete(keep = confirmed && result.videoSamples > 0L)
    }

    private fun abandonStreams() {
        original.abandon()
        processed.abandon()
        audio.abandon()
        worker.shutdownNow()
    }

    private fun preserveOrDelete(keep: Boolean) {
        if (keep) updateJournal(RecordingJournal.Status.FAILED) else directory.deleteRecursively()
    }

    private fun report(reason: RecordingFailure, error: Exception?) {
        RecordingLog.w("recording_failed reason=$reason type=${error?.javaClass?.simpleName}")
        if (!stopped.get() && reported.compareAndSet(false, true)) onFailure(reason)
    }

    private fun onProcessedProbe(estimator: RecordingLatencyEstimator, timeUs: Long, grid: FloatArray) {
        estimator.addProcessed(timeUs, grid)
        if (processedProbes.incrementAndGet() % ESTIMATE_EVERY_FRAMES != 0 || !estimating.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    val previous = journal.processedDelayUs
                    estimator.estimate()?.let { delay ->
                        if (abs(delay - previous) >= JOURNAL_DELAY_STEP_US) updateJournal(delayUs = delay)
                    }
                } catch (error: Exception) {
                    RecordingLog.w("alignment_estimate_failed type=${error.javaClass.simpleName}")
                } finally {
                    estimating.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            estimating.set(false)
        }
    }

    /** 영상 하나의 인코더입니다. 첫 프레임 크기로 작업 스레드에서 인코더를 만들고 그동안의 프레임은 버립니다. */
    private inner class VideoStream(
        private val name: String,
        private val writer: RecordingTrackWriter,
        private val fatal: Boolean,
        private val probe: ((Long, FloatArray) -> Unit)?,
    ) {
        private val lock = Any()
        private var pipeline: RecordingVideoPipeline? = null
        private var starting = false
        private var closed = false
        @Volatile private var failed = false
        private val setupDone = CountDownLatch(1)
        private val endOfStream = CountDownLatch(1)
        private val lastPtsNs = AtomicLong(Long.MIN_VALUE)
        val dropped = AtomicLong()
        @Volatile var formatSeen = false
            private set

        fun onFrame(frame: VideoFrame) {
            val target = synchronized(lock) {
                if (closed || failed) return
                pipeline ?: run {
                    if (!starting) {
                        starting = true
                        scheduleStart(frame.rotatedWidth, frame.rotatedHeight)
                    }
                    null
                }
            }
            val pts = cameraTimestampToMonotonicNs(frame.timestampNs, nowNs(), realtimeNs())
            probe?.let { sink ->
                try {
                    RecordingFrameProbe.lumaGrid(frame.buffer)?.let { sink(pts / 1_000L, it) }
                } catch (error: Exception) {
                    RecordingLog.w("${name}_probe_failed type=${error.javaClass.simpleName}")
                }
            }
            if (target == null) {
                dropped.incrementAndGet()
                return
            }
            val previous = lastPtsNs.get()
            if (pts <= previous || !lastPtsNs.compareAndSet(previous, pts)) {
                dropped.incrementAndGet()
                return
            }
            target.render(frame, pts)
        }

        /** 잠금 안에서 부릅니다. MediaCodec·EGL 준비는 최대 수 초 걸리므로 작업 스레드로 넘깁니다. */
        private fun scheduleStart(sourceWidth: Int, sourceHeight: Int) {
            val size = recordingSizeFor(sourceWidth, sourceHeight)
            try {
                worker.execute {
                    val created = try {
                        media.createVideo(name, size, recordingVideoBitrateFor(size), output)
                    } catch (error: Exception) {
                        fail(error)
                        null
                    }
                    val keep = synchronized(lock) {
                        if (created != null && !closed && !failed) { pipeline = created; true } else false
                    }
                    if (!keep) runCatching { created?.close() }
                    setupDone.countDown()
                }
            } catch (error: RejectedExecutionException) {
                setupDone.countDown()
                fail(error)
            }
        }

        private val output = object : RecordingVideoOutput {
            override fun onConfig(config: VideoTrackConfig) {
                formatSeen = true
                writer.setVideoConfig(config)
            }

            override fun onPacket(packet: EncodedPacket) = writer.writeVideo(packet)

            override fun onEndOfStream() = endOfStream.countDown()

            override fun onDropped() {
                dropped.incrementAndGet()
            }

            override fun onError(error: Exception) = fail(error)
        }

        private fun fail(error: Exception) {
            synchronized(lock) {
                if (failed) return
                failed = true
            }
            endOfStream.countDown()
            if (fatal) report(RecordingFailure.ENCODER, error)
            else RecordingLog.w("${name}_stream_failed type=${error.javaClass.simpleName}")
        }

        fun finish() {
            val wasStarting = synchronized(lock) {
                closed = true
                starting
            }
            if (wasStarting) setupDone.await(SETUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            val current = synchronized(lock) { pipeline }
            if (current != null && !failed) {
                current.signalEndOfInput()
                if (!endOfStream.await(finishTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    RecordingLog.w("${name}_end_of_stream_timeout")
                }
            }
            runCatching { current?.close() }
        }

        fun abandon() {
            val current = synchronized(lock) {
                closed = true
                pipeline
            }
            runCatching { current?.close() }
        }
    }

    /** 송출 음성 하나의 AAC 인코더입니다. 48kHz 스테레오로 바꿔 두 파일에 같은 출력을 씁니다. */
    private inner class AudioStream {
        private val lock = Any()
        private var encoder: RecordingAudioEncoder? = null
        private var starting = false
        private var closed = false
        @Volatile private var failed = false
        private val converter = RecordingAudioConverter()
        private val clock = RecordingAudioClock()
        private val setupDone = CountDownLatch(1)
        private val endOfStream = CountDownLatch(1)
        @Volatile var formatSeen = false
            private set

        fun onPcm(data: ByteArray, size: Int, rate: Int, channelCount: Int, presentationTimeUs: Long) {
            if (size <= 0 || rate <= 0 || channelCount !in 1..2) return
            synchronized(lock) {
                if (closed || failed) return
                val target = encoder ?: run {
                    if (!starting) {
                        starting = true
                        scheduleStart()
                    }
                    null
                } ?: return
                val stereo = converter.convert(data, size, rate, channelCount)
                val frames = stereo.size / RECORDING_AUDIO_CHANNELS
                if (frames == 0) return
                val placement = clock.place(presentationTimeUs, frames)
                var silenceFrame = placement.silenceStartFrame
                var silenceLeft = placement.silenceFrames
                while (silenceLeft > 0) {
                    val count = minOf(silenceLeft, MAX_AUDIO_CHUNK_FRAMES.toLong()).toInt()
                    target.queueSilence(count * BYTES_PER_FRAME, clock.timeOfFrame(silenceFrame))
                    silenceFrame += count
                    silenceLeft -= count
                }
                var offset = placement.skipFrames
                var frame = placement.chunkStartFrame
                while (offset < frames) {
                    val count = minOf(frames - offset, MAX_AUDIO_CHUNK_FRAMES)
                    val bytes = ByteArray(count * BYTES_PER_FRAME)
                    for (index in 0 until count * RECORDING_AUDIO_CHANNELS) {
                        val value = stereo[offset * RECORDING_AUDIO_CHANNELS + index].toInt()
                        bytes[index * 2] = value.toByte()
                        bytes[index * 2 + 1] = (value shr 8).toByte()
                    }
                    target.queuePcm(bytes, bytes.size, clock.timeOfFrame(frame))
                    offset += count
                    frame += count
                }
            }
        }

        /** 잠금 안에서 부릅니다. */
        private fun scheduleStart() {
            try {
                worker.execute {
                    val created = try {
                        media.createAudio(output)
                    } catch (error: Exception) {
                        fail(error)
                        null
                    }
                    val keep = synchronized(lock) {
                        if (created != null && !closed && !failed) { encoder = created; true } else false
                    }
                    if (!keep) runCatching { created?.close() }
                    setupDone.countDown()
                }
            } catch (error: RejectedExecutionException) {
                setupDone.countDown()
                fail(error)
            }
        }

        private val output = object : RecordingAudioOutput {
            override fun onConfig(config: AudioTrackConfig) {
                formatSeen = true
                originalWriter.setAudioConfig(config)
                processedWriter.setAudioConfig(config)
            }

            override fun onPacket(packet: EncodedPacket) {
                originalWriter.writeAudio(packet)
                processedWriter.writeAudio(packet)
            }

            override fun onEndOfStream() = endOfStream.countDown()

            override fun onError(error: Exception) = fail(error)
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
            val wasStarting = synchronized(lock) {
                closed = true
                starting
            }
            if (wasStarting) setupDone.await(SETUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            val current = synchronized(lock) { encoder }
            if (current != null && !failed) {
                current.signalEndOfInput()
                if (!endOfStream.await(finishTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    RecordingLog.w("audio_end_of_stream_timeout")
                }
            }
            runCatching { current?.close() }
        }

        fun abandon() {
            val current = synchronized(lock) {
                closed = true
                encoder
            }
            runCatching { current?.close() }
        }
    }

    companion object {
        const val ORIGINAL_FILE_NAME = "original"
        const val PROCESSED_FILE_NAME = "processed"
        private const val FINISH_TIMEOUT_MILLIS = 3_000L
        private const val SETUP_TIMEOUT_MILLIS = 6_000L
        /** 첫 프레임 시각이 세션 시작보다 조금 이를 수 있어 기준 시각을 앞당깁니다. */
        private const val EPOCH_MARGIN_US = 5_000_000L
        private const val MAX_AUDIO_CHUNK_FRAMES = 2_048
        private const val BYTES_PER_FRAME = RECORDING_AUDIO_CHANNELS * 2
        private const val ESTIMATE_EVERY_FRAMES = 60
        private const val JOURNAL_DELAY_STEP_US = 5_000L

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
