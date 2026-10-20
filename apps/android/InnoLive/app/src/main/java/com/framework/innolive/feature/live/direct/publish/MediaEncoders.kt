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

    /** 코덱의 실제 출력 형식입니다. MP4 먹서처럼 csd가 포함된 형식이 필요한 쪽이 씁니다. */
    fun onOutputFormat(format: MediaFormat) = Unit
    fun onPacket(packet: EncodedPacket)

    /** 입력 종료를 알린 뒤 마지막 출력까지 내보냈습니다. 녹화 종료가 기다립니다. */
    fun onEndOfStream() = Unit
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

    /** [close] 뒤에는 코덱이 멈춰 입력·출력 호출이 IllegalStateException을 던집니다. */
    protected val isReleased: Boolean get() = released

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
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (released) return
                try {
                    onInputAvailable(index)
                } catch (error: Exception) {
                    fail(error)
                }
            }

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
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) output.onEndOfStream()
                } catch (error: Exception) {
                    fail(error)
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                output.onOutputFormat(format)
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

    /** 종료 중 경쟁으로 난 코덱 상태 예외는 무시합니다. 인코더 스레드에서 던지면 앱이 종료됩니다. */
    protected fun fail(error: Exception) {
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
internal class AacEncoder(
    sampleRate: Int,
    channels: Int,
    bitrateBps: Int,
    output: EncoderOutput,
    name: String = "direct-publish-audio",
    /** 입력이 밀릴 때 보관할 청크 수입니다. 넘으면 오래된 청크부터 버립니다. */
    private val maxPendingChunks: Int = MAX_PENDING_CHUNKS,
) : AsyncMediaEncoder(name, output) {
    constructor(profile: DirectPublishProfile, output: EncoderOutput) :
        this(profile.audioSampleRate, profile.audioChannels, profile.audioBitrateBps, output)

    private class Chunk(
        val data: ByteArray?,
        val size: Int,
        val presentationTimeUs: Long,
        val endOfStream: Boolean = false,
    )

    private val pending = ArrayDeque<Chunk>()
    private val freeInputs = ArrayDeque<Int>()
    private val lock = Any()

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        initializeOrClose { configure(format, MediaFormat.MIMETYPE_AUDIO_AAC, surfaceInput = false) }
    }

    /** 오디오 캡처 스레드에서 부릅니다. [data]는 이 호출 안에서 복사합니다. */
    fun queuePcm(data: ByteArray, size: Int, presentationTimeUs: Long) =
        enqueue(Chunk(data.copyOf(size), size, presentationTimeUs))

    /** [size]바이트의 무음을 넣습니다. 데이터를 복사하지 않으므로 긴 공백도 메모리를 쓰지 않습니다. */
    fun queueSilence(size: Int, presentationTimeUs: Long) {
        require(size in 0..MAX_INPUT_BYTES)
        enqueue(Chunk(null, size, presentationTimeUs))
    }

    private fun enqueue(chunk: Chunk) {
        synchronized(lock) {
            if (pending.size >= maxPendingChunks) pending.removeFirst()
            pending.addLast(chunk)
        }
        handler.post(::drain)
    }

    /** 남은 청크 뒤에 입력 종료를 넣습니다. 마지막 출력이 나오면 [EncoderOutput.onEndOfStream]이 옵니다. */
    fun signalEndOfInput() {
        synchronized(lock) { pending.addLast(Chunk(null, 0, 0L, endOfStream = true)) }
        handler.post(::drain)
    }

    override fun onInputAvailable(index: Int) {
        synchronized(lock) { freeInputs.addLast(index) }
        drain()
    }

    /** 인코더 스레드에서만 부릅니다. 종료와 겹친 예외는 [fail]이 걸러 냅니다. */
    private fun drain() {
        try {
            drainInputs()
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun drainInputs() {
        while (!isReleased) {
            val (index, chunk) = synchronized(lock) {
                if (freeInputs.isEmpty() || pending.isEmpty()) return
                freeInputs.removeFirst() to pending.removeFirst()
            }
            val input = codec.getInputBuffer(index) ?: continue
            input.clear()
            if (chunk.endOfStream) {
                codec.queueInputBuffer(index, 0, 0, chunk.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            val size = minOf(chunk.size, input.remaining())
            input.put(chunk.data ?: SILENCE, 0, size)
            codec.queueInputBuffer(index, 0, size, chunk.presentationTimeUs, 0)
        }
    }

    internal companion object {
        const val MAX_INPUT_BYTES = 16 * 1024
        const val MAX_PENDING_CHUNKS = 50
        private val SILENCE = ByteArray(MAX_INPUT_BYTES)
    }
}
