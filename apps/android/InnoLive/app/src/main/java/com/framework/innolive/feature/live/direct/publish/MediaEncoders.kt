package com.framework.innolive.feature.live.direct.publish

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer

internal interface EncoderOutput {
    fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer) = Unit
    fun onPacket(packet: EncodedPacket)
    fun onError(error: Exception)
}

/**
 * MediaCodec 비동기 콜백을 전용 스레드에서 처리합니다. 출력 버퍼는 [EncoderOutput.onPacket]이
 * 끝난 뒤 바로 돌려주므로 받는 쪽은 그 안에서 데이터를 복사하거나 전송을 끝내야 합니다.
 */
internal abstract class AsyncMediaEncoder(
    name: String,
    private val output: EncoderOutput,
    /** 코덱 생성입니다. 기기 테스트가 구성 실패를 재현할 때만 바꿉니다. */
    private val codecFactory: (String) -> MediaCodec = MediaCodec::createEncoderByType,
) : AutoCloseable {
    private val thread = HandlerThread(name).apply { start() }
    protected val handler = Handler(thread.looper)
    protected lateinit var codec: MediaCodec
    @Volatile private var released = false

    /** 실패하면 이번에 만든 코덱을 해제하고 예외를 그대로 던집니다. */
    protected fun configure(format: MediaFormat, mime: String, surfaceInput: Boolean) {
        val created = codecFactory(mime)
        codec = created
        try {
            configureCodec(format, surfaceInput)
        } catch (error: Exception) {
            runCatching { created.release() }
            throw error
        }
    }

    private fun configureCodec(format: MediaFormat, surfaceInput: Boolean) {
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = onInputAvailable(index)

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        buffer.position(info.offset).limit(info.offset + info.size)
                        output.onPacket(EncodedPacket(buffer, info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (error: Exception) {
                    fail(error)
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val sps = format.getByteBuffer("csd-0")
                val pps = format.getByteBuffer("csd-1")
                if (sps != null && pps != null) output.onVideoConfig(sps, pps)
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = fail(error)
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (surfaceInput) createSurface()
    }

    protected open fun createSurface() = Unit

    protected open fun onInputAvailable(index: Int) = Unit

    fun start() = codec.start()

    private fun fail(error: Exception) {
        if (!released) output.onError(error)
    }

    override fun close() {
        if (released) return
        released = true
        if (::codec.isInitialized) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        thread.quitSafely()
    }

    /** 하위 클래스 생성 중 실패하면 시작한 스레드와 코덱을 정리합니다. 호출자는 인스턴스를 받지 못해 닫을 수 없습니다. */
    protected inline fun initializeOrClose(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            close()
            throw error
        }
    }
}

/** 가림 처리된 프레임을 입력 Surface로 받는 H.264 인코더입니다. */
internal class H264SurfaceEncoder(
    profile: DirectPublishProfile,
    output: EncoderOutput,
    codecFactory: (String) -> MediaCodec = MediaCodec::createEncoderByType,
) : AsyncMediaEncoder("direct-publish-video", output, codecFactory) {
    lateinit var inputSurface: Surface
        private set

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, profile.width, profile.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.videoBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, profile.keyFrameIntervalSeconds)
            // 실시간 송출은 화질보다 일정한 전송량이 중요합니다.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            // 입력이 멈춰도 키프레임 간격을 지키도록 마지막 프레임을 반복합니다.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / profile.fps * 2)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        initializeOrClose {
            try {
                configure(format, MediaFormat.MIMETYPE_VIDEO_AVC, surfaceInput = true)
            } catch (error: Exception) {
                // 일부 기기는 High 프로파일 지정을 거부합니다. 기본 프로파일로 다시 설정합니다.
                format.removeKey(MediaFormat.KEY_PROFILE)
                format.removeKey(MediaFormat.KEY_LEVEL)
                configure(format, MediaFormat.MIMETYPE_VIDEO_AVC, surfaceInput = true)
            }
        }
    }

    override fun createSurface() {
        inputSurface = codec.createInputSurface()
    }

    fun requestKeyFrame() {
        handler.post {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }
}

/** 마이크 PCM(16비트)을 AAC-LC로 인코딩합니다. 입력이 밀리면 오래된 청크부터 버립니다. */
internal class AacEncoder(private val profile: DirectPublishProfile, output: EncoderOutput) :
    AsyncMediaEncoder("direct-publish-audio", output) {
    private class Chunk(val data: ByteArray, val size: Int, val presentationTimeUs: Long)

    private val pending = ArrayDeque<Chunk>()
    private val freeInputs = ArrayDeque<Int>()
    private val lock = Any()

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, profile.audioSampleRate,
            profile.audioChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.audioBitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        initializeOrClose { configure(format, MediaFormat.MIMETYPE_AUDIO_AAC, surfaceInput = false) }
    }

    /** 오디오 캡처 스레드에서 부릅니다. [data]는 이 호출 안에서 복사합니다. */
    fun queuePcm(data: ByteArray, size: Int, presentationTimeUs: Long) {
        val chunk = Chunk(data.copyOf(size), size, presentationTimeUs)
        synchronized(lock) {
            if (pending.size >= MAX_PENDING_CHUNKS) pending.removeFirst()
            pending.addLast(chunk)
        }
        handler.post(::drain)
    }

    override fun onInputAvailable(index: Int) {
        synchronized(lock) { freeInputs.addLast(index) }
        drain()
    }

    private fun drain() {
        while (true) {
            val (index, chunk) = synchronized(lock) {
                if (freeInputs.isEmpty() || pending.isEmpty()) return
                freeInputs.removeFirst() to pending.removeFirst()
            }
            val input = codec.getInputBuffer(index) ?: continue
            input.clear()
            val size = minOf(chunk.size, input.remaining())
            input.put(chunk.data, 0, size)
            codec.queueInputBuffer(index, 0, size, chunk.presentationTimeUs, 0)
        }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 16 * 1024
        const val MAX_PENDING_CHUNKS = 50
    }
}
