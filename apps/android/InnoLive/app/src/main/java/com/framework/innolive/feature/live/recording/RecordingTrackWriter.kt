package com.framework.innolive.feature.live.recording

import com.framework.innolive.feature.live.direct.publish.EncodedPacket
import java.io.File

/** 한 MP4 파일의 쓰기 결과입니다. [started]가 false면 두 트랙 형식을 모두 받지 못해 파일을 만들지 못한 것입니다. */
internal data class RecordingWriterResult(
    val started: Boolean,
    val videoSamples: Long,
    val audioSamples: Long,
    val droppedSamples: Long,
    val failure: Exception?,
    val failureReason: RecordingFailure?,
)

/**
 * 영상·음성 트랙 하나씩을 가진 녹화 파일입니다. 두 인코더의 형식을 모두 받은 뒤 [FragmentedMp4Writer]를
 * 열고, 그 전의 출력은 힙에 복사해 [maxPendingBytes]·[maxPendingDurationUs]까지만 보관합니다. 한도를 넘기면
 * 오지 않은 형식에 맞춰 [RecordingFailure.NO_AUDIO]·[RecordingFailure.NO_VIDEO]로 실패합니다.
 *
 * 영상은 첫 키프레임부터, 각 트랙은 시각이 증가하는 표본만 기록합니다. 인코더 출력 스레드 둘에서 부르므로
 * 모든 상태를 한 잠금으로 보호합니다. 파일은 [stop] 뒤 [Mp4Finalizer]가 일반 MP4로 바꿉니다.
 */
internal class RecordingTrackWriter(
    private val file: File,
    private val epochUs: Long,
    private val onError: (RecordingFailure, Exception) -> Unit,
    private val maxPendingBytes: Long = DEFAULT_MAX_PENDING_BYTES,
    private val maxPendingDurationUs: Long = DEFAULT_MAX_PENDING_DURATION_US,
    private val writerFactory: (File, VideoTrackConfig, AudioTrackConfig, Long) -> FragmentedMp4Writer =
        { target, video, audio, epoch -> FragmentedMp4Writer(target, video, audio, epoch) },
) {
    private class Pending(val isVideo: Boolean, val data: ByteArray, val presentationTimeUs: Long, val isKeyFrame: Boolean)

    private val lock = Any()
    private var writer: FragmentedMp4Writer? = null
    private var videoConfig: VideoTrackConfig? = null
    private var audioConfig: AudioTrackConfig? = null
    private var started = false
    private var finished = false
    private var failure: Exception? = null
    private var failureReason: RecordingFailure? = null
    private val pending = ArrayDeque<Pending>()
    private var pendingBytes = 0L
    private var videoKeyFrameSeen = false
    private var dropped = 0L

    val hasVideoFormat: Boolean get() = synchronized(lock) { videoConfig != null }
    val hasAudioFormat: Boolean get() = synchronized(lock) { audioConfig != null }

    /** 대기 중인 출력의 바이트 수입니다. 테스트가 메모리 한도를 확인합니다. */
    val pendingByteCount: Long get() = synchronized(lock) { pendingBytes }

    fun setVideoConfig(config: VideoTrackConfig) = synchronized(lock) {
        if (finished || failure != null || videoConfig != null) return
        videoConfig = config
        tryStart()
    }

    fun setAudioConfig(config: AudioTrackConfig) = synchronized(lock) {
        if (finished || failure != null || audioConfig != null) return
        audioConfig = config
        tryStart()
    }

    /** [packet]은 MediaCodec의 Annex-B H.264 출력입니다. 호출 안에서 복사합니다. */
    fun writeVideo(packet: EncodedPacket) {
        if (synchronized(lock) { finished || failure != null }) return
        val sample = Mp4Codec.annexBToLengthPrefixed(packet.data) ?: return
        write(Pending(true, sample, packet.presentationTimeUs, packet.isKeyFrame))
    }

    fun writeAudio(packet: EncodedPacket) {
        if (synchronized(lock) { finished || failure != null }) return
        val data = ByteArray(packet.data.remaining())
        packet.data.duplicate().get(data)
        write(Pending(false, data, packet.presentationTimeUs, true))
    }

    private fun write(sample: Pending) = synchronized(lock) {
        if (finished || failure != null) return
        if (!started) {
            pending.addLast(sample)
            pendingBytes += sample.data.size
            val span = pending.maxOf { it.presentationTimeUs } - pending.minOf { it.presentationTimeUs }
            if (pendingBytes > maxPendingBytes || span > maxPendingDurationUs) {
                val reason = when {
                    audioConfig == null && videoConfig != null -> RecordingFailure.NO_AUDIO
                    videoConfig == null && audioConfig != null -> RecordingFailure.NO_VIDEO
                    pending.any { it.isVideo } -> RecordingFailure.NO_AUDIO
                    else -> RecordingFailure.NO_VIDEO
                }
                fail(reason, IllegalStateException("녹화 트랙 형식을 기다리는 동안 대기 한도를 넘었습니다."))
            }
            return
        }
        writeSample(sample)
    }

    /** 잠금 안에서 부릅니다. */
    private fun tryStart() {
        val video = videoConfig ?: return
        val audio = audioConfig ?: return
        try {
            writer = writerFactory(file, video, audio, epochUs)
            started = true
            // 영상·음성 대기 출력을 시각순으로 넣어 조각이 두 트랙을 고르게 담게 합니다.
            pending.sortedBy { it.presentationTimeUs }.forEach { if (failure == null) writeSample(it) }
            pending.clear()
            pendingBytes = 0L
        } catch (error: Exception) {
            fail(RecordingFailure.STORAGE, error)
        }
    }

    /** 잠금 안에서 부릅니다. */
    private fun writeSample(sample: Pending) {
        val current = writer ?: return
        if (sample.isVideo && !videoKeyFrameSeen) {
            if (!sample.isKeyFrame) { dropped++; return }
            videoKeyFrameSeen = true
        }
        val accepted = try {
            if (sample.isVideo) current.writeVideo(sample.data, sample.presentationTimeUs, sample.isKeyFrame)
            else current.writeAudio(sample.data, sample.presentationTimeUs)
        } catch (error: Exception) {
            fail(RecordingFailure.STORAGE, error)
            return
        }
        if (!accepted) dropped++
    }

    /** 잠금 안에서 부릅니다. 실패 뒤의 출력은 모두 버립니다. 이미 쓴 조각은 파일에 남습니다. */
    private fun fail(reason: RecordingFailure, error: Exception) {
        if (failure != null) return
        failure = error
        failureReason = reason
        pending.clear()
        pendingBytes = 0L
        runCatching { writer?.close() }
        onError(reason, error)
    }

    /** 인코더를 모두 끝낸 뒤 부릅니다. 남은 표본을 마지막 조각으로 쓰고 파일을 닫습니다. */
    fun stop(): RecordingWriterResult = synchronized(lock) {
        if (!finished) {
            finished = true
            pending.clear()
            pendingBytes = 0L
            val current = writer
            if (current != null && failure == null) {
                try {
                    current.finish()
                } catch (error: Exception) {
                    failure = error
                    failureReason = RecordingFailure.STORAGE
                }
            } else {
                runCatching { current?.close() }
            }
        }
        val current = writer
        RecordingWriterResult(
            started = started && failure == null,
            videoSamples = current?.videoSamples ?: 0L,
            audioSamples = current?.audioSamples ?: 0L,
            droppedSamples = dropped,
            failure = failure,
            failureReason = failureReason,
        )
    }

    /** 파일을 버립니다. */
    fun abort() {
        synchronized(lock) {
            finished = true
            pending.clear()
            pendingBytes = 0L
            runCatching { writer?.close() }
        }
        file.delete()
    }

    internal companion object {
        /** 두 형식이 오기 전 보관할 출력 크기입니다. 보통 첫 0.2초 안에 두 형식이 모두 옵니다. */
        const val DEFAULT_MAX_PENDING_BYTES = 4L * 1024 * 1024
        const val DEFAULT_MAX_PENDING_DURATION_US = 5_000_000L
    }
}
