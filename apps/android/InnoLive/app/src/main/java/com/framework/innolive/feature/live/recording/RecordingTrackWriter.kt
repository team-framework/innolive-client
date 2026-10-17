package com.framework.innolive.feature.live.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.framework.innolive.feature.live.direct.publish.EncodedPacket
import java.io.File
import java.nio.ByteBuffer

/** 한 MP4 파일의 쓰기 결과입니다. [started]가 false면 두 트랙 형식을 모두 받지 못해 파일을 만들지 못한 것입니다. */
internal data class RecordingWriterResult(
    val started: Boolean,
    val videoSamples: Long,
    val audioSamples: Long,
    val durationUs: Long,
    val failure: Exception?,
)

/**
 * 영상·음성 트랙 하나씩을 가진 MP4 먹서입니다. 두 인코더의 출력 형식을 모두 받은 뒤 시작하며 그 전의
 * 출력은 복사해 두었다가 씁니다. 영상은 첫 키프레임부터, 각 트랙은 시각이 증가하는 샘플만 기록합니다.
 * 인코더 출력 스레드 둘에서 부르므로 모든 상태를 한 잠금으로 보호합니다.
 */
internal class RecordingTrackWriter(
    private val file: File,
    private val onError: (Exception) -> Unit,
) {
    private class Pending(val isVideo: Boolean, val data: ByteBuffer, val presentationTimeUs: Long, val isKeyFrame: Boolean)

    private val lock = Any()
    private var muxer: MediaMuxer? = null
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var finished = false
    private var failure: Exception? = null
    private val pending = ArrayDeque<Pending>()
    private var videoKeyFrameSeen = false
    private var lastVideoUs = Long.MIN_VALUE
    private var lastAudioUs = Long.MIN_VALUE
    private var firstUs = Long.MAX_VALUE
    private var lastUs = Long.MIN_VALUE
    private var videoSamples = 0L
    private var audioSamples = 0L
    private val info = MediaCodec.BufferInfo()

    val hasVideoFormat: Boolean get() = synchronized(lock) { videoFormat != null }
    val hasAudioFormat: Boolean get() = synchronized(lock) { audioFormat != null }

    fun setVideoFormat(format: MediaFormat) = synchronized(lock) {
        if (finished || failure != null || videoFormat != null) return
        videoFormat = format
        tryStart()
    }

    fun setAudioFormat(format: MediaFormat) = synchronized(lock) {
        if (finished || failure != null || audioFormat != null) return
        audioFormat = format
        tryStart()
    }

    fun writeVideo(packet: EncodedPacket) = write(isVideo = true, packet)

    fun writeAudio(packet: EncodedPacket) = write(isVideo = false, packet)

    private fun write(isVideo: Boolean, packet: EncodedPacket) = synchronized(lock) {
        if (finished || failure != null) return
        if (!started) {
            if (pending.size >= MAX_PENDING_PACKETS) {
                fail(IllegalStateException("녹화 트랙 형식을 받지 못해 먹서를 시작하지 못했습니다."))
                return
            }
            pending.addLast(Pending(isVideo, packet.data.copyRemaining(), packet.presentationTimeUs, packet.isKeyFrame))
            return
        }
        writeSample(isVideo, packet.data, packet.presentationTimeUs, packet.isKeyFrame)
    }

    /** 잠금 안에서 부릅니다. */
    private fun tryStart() {
        val video = videoFormat ?: return
        val audio = audioFormat ?: return
        try {
            val created = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = created
            videoTrack = created.addTrack(video)
            audioTrack = created.addTrack(audio)
            created.start()
            started = true
            while (pending.isNotEmpty()) {
                val sample = pending.removeFirst()
                writeSample(sample.isVideo, sample.data, sample.presentationTimeUs, sample.isKeyFrame)
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    /** 잠금 안에서 부릅니다. */
    private fun writeSample(isVideo: Boolean, data: ByteBuffer, presentationTimeUs: Long, isKeyFrame: Boolean) {
        val current = muxer ?: return
        if (isVideo) {
            if (!videoKeyFrameSeen) {
                if (!isKeyFrame) return
                videoKeyFrameSeen = true
            }
            if (presentationTimeUs <= lastVideoUs) return
            lastVideoUs = presentationTimeUs
        } else {
            if (presentationTimeUs <= lastAudioUs) return
            lastAudioUs = presentationTimeUs
        }
        info.set(data.position(), data.remaining(), presentationTimeUs,
            if (isVideo && isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        try {
            current.writeSampleData(if (isVideo) videoTrack else audioTrack, data, info)
        } catch (error: Exception) {
            fail(error)
            return
        }
        if (isVideo) videoSamples++ else audioSamples++
        firstUs = minOf(firstUs, presentationTimeUs)
        lastUs = maxOf(lastUs, presentationTimeUs)
    }

    /** 잠금 안에서 부릅니다. 실패 뒤의 출력은 모두 버립니다. */
    private fun fail(error: Exception) {
        if (failure != null) return
        failure = error
        pending.clear()
        releaseMuxer()
        onError(error)
    }

    private fun releaseMuxer() {
        val current = muxer ?: return
        muxer = null
        runCatching { if (started) current.stop() }
        runCatching { current.release() }
    }

    /** 인코더를 모두 끝낸 뒤 부릅니다. 샘플이 없는 먹서는 stop에서 예외를 던지므로 실패로 돌려줍니다. */
    fun stop(): RecordingWriterResult = synchronized(lock) {
        if (!finished) {
            finished = true
            pending.clear()
            val current = muxer
            muxer = null
            if (current != null) {
                try {
                    if (started) current.stop()
                } catch (error: Exception) {
                    if (failure == null) failure = error
                } finally {
                    runCatching { current.release() }
                }
            }
        }
        RecordingWriterResult(
            started = started && failure == null,
            videoSamples = videoSamples,
            audioSamples = audioSamples,
            durationUs = if (lastUs >= firstUs) lastUs - firstUs else 0L,
            failure = failure,
        )
    }

    /** 파일을 버립니다. 기록 중이던 먹서는 멈추지 않고 해제합니다. */
    fun abort() {
        synchronized(lock) {
            finished = true
            pending.clear()
            val current = muxer
            muxer = null
            runCatching { current?.release() }
        }
        file.delete()
    }

    private companion object {
        /** 두 형식이 도착하기 전 보관하는 출력 수입니다. 보통 첫 1초 안에 두 형식이 모두 옵니다. */
        const val MAX_PENDING_PACKETS = 1_500
    }
}

private fun ByteBuffer.copyRemaining(): ByteBuffer {
    val source = duplicate()
    return ByteBuffer.allocateDirect(source.remaining()).put(source).apply { flip() }
}
