package com.framework.innolive.feature.live.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.framework.innolive.feature.live.direct.publish.AacEncoder
import com.framework.innolive.feature.live.direct.publish.AsyncMediaEncoder
import com.framework.innolive.feature.live.direct.publish.EncodedPacket
import com.framework.innolive.feature.live.direct.publish.EncoderOutput
import com.framework.innolive.feature.live.direct.publish.EncoderSurfaceRenderer
import org.webrtc.EglBase
import org.webrtc.VideoFrame

/** 녹화 인코더 출력 크기입니다. 긴 변을 [MAX_RECORDING_LONG_EDGE] 이하로 맞추고 짝수로 만듭니다. */
internal data class RecordingSize(val width: Int, val height: Int)

internal const val MAX_RECORDING_LONG_EDGE = 1920

internal fun recordingSizeFor(sourceWidth: Int, sourceHeight: Int): RecordingSize {
    require(sourceWidth > 0 && sourceHeight > 0)
    val longEdge = maxOf(sourceWidth, sourceHeight)
    val scale = if (longEdge > MAX_RECORDING_LONG_EDGE) MAX_RECORDING_LONG_EDGE.toDouble() / longEdge else 1.0
    fun even(value: Int) = (value - value % 2).coerceAtLeast(2)
    return RecordingSize(even((sourceWidth * scale).toInt()), even((sourceHeight * scale).toInt()))
}

/** iOS `LocalBroadcastRecorder`와 같이 첫 프레임 크기의 화소당 4bps를 2~12Mbps로 제한합니다. */
internal fun recordingVideoBitrateFor(size: RecordingSize): Int =
    (size.width.toLong() * size.height * 4).coerceIn(MIN_RECORDING_BITRATE_BPS, MAX_RECORDING_BITRATE_BPS).toInt()

internal const val MIN_RECORDING_BITRATE_BPS = 2_000_000L
internal const val MAX_RECORDING_BITRATE_BPS = 12_000_000L
internal const val RECORDING_AUDIO_BITRATE_BPS = 128_000
internal const val RECORDING_FPS = 30

/** 녹화 영상 인코더 출력입니다. 형식은 MP4 트랙 설정으로 바꿔 넘깁니다. */
internal interface RecordingVideoOutput {
    fun onConfig(config: VideoTrackConfig)
    fun onPacket(packet: EncodedPacket)
    fun onEndOfStream()
    fun onDropped()
    fun onError(error: Exception)
}

internal interface RecordingAudioOutput {
    fun onConfig(config: AudioTrackConfig)
    fun onPacket(packet: EncodedPacket)
    fun onEndOfStream()
    fun onError(error: Exception)
}

/** 프레임을 그려 H.264로 인코딩합니다. [render]는 프레임을 붙잡아 다른 스레드에서 그립니다. */
internal interface RecordingVideoPipeline : AutoCloseable {
    fun render(frame: VideoFrame, presentationTimeNs: Long)

    /** 더 그리지 않습니다. 마지막 출력 뒤 [RecordingVideoOutput.onEndOfStream]이 옵니다. */
    fun signalEndOfInput()
}

/** 48kHz 스테레오 PCM 16비트를 AAC로 인코딩합니다. */
internal interface RecordingAudioEncoder : AutoCloseable {
    fun queuePcm(data: ByteArray, size: Int, presentationTimeUs: Long)
    fun queueSilence(size: Int, presentationTimeUs: Long)
    fun signalEndOfInput()
}

/** 녹화 인코더를 만듭니다. 단위 테스트는 가짜 인코더를 넣습니다. 작업 스레드에서 부릅니다. */
internal interface RecordingMediaFactory {
    fun createVideo(name: String, size: RecordingSize, bitrateBps: Int, output: RecordingVideoOutput): RecordingVideoPipeline
    fun createAudio(output: RecordingAudioOutput): RecordingAudioEncoder
}

/** MediaCodec과 송출 경로의 EGL 문맥을 쓰는 기본 녹화 인코더입니다. */
internal class AndroidRecordingMediaFactory(private val sharedEglContext: EglBase.Context) : RecordingMediaFactory {
    override fun createVideo(
        name: String,
        size: RecordingSize,
        bitrateBps: Int,
        output: RecordingVideoOutput,
    ): RecordingVideoPipeline {
        val encoder = RecordingVideoEncoder(size.width, size.height, RECORDING_FPS, bitrateBps, object : EncoderOutput {
            override fun onOutputFormat(format: MediaFormat) {
                try {
                    output.onConfig(videoTrackConfig(format, size))
                } catch (error: Exception) {
                    output.onError(error)
                }
            }

            override fun onPacket(packet: EncodedPacket) = output.onPacket(packet)

            override fun onEndOfStream() = output.onEndOfStream()

            override fun onError(error: Exception) = output.onError(error)
        })
        val renderer = try {
            EncoderSurfaceRenderer(sharedEglContext, encoder.inputSurface, size.width, size.height,
                onRendered = {}, onDropped = output::onDropped, onError = output::onError)
        } catch (error: Exception) {
            encoder.close()
            throw error
        }
        try {
            encoder.start()
        } catch (error: Exception) {
            renderer.close()
            encoder.close()
            throw error
        }
        RecordingLog.i("video_stream_started name=$name size=${size.width}x${size.height} bitrate=$bitrateBps")
        return object : RecordingVideoPipeline {
            override fun render(frame: VideoFrame, presentationTimeNs: Long) = renderer.render(frame, presentationTimeNs)

            override fun signalEndOfInput() {
                runCatching { renderer.close() }
                encoder.signalEndOfInputStream()
            }

            override fun close() {
                runCatching { renderer.close() }
                runCatching { encoder.close() }
            }
        }
    }

    override fun createAudio(output: RecordingAudioOutput): RecordingAudioEncoder {
        val encoder = AacEncoder(RECORDING_AUDIO_SAMPLE_RATE, RECORDING_AUDIO_CHANNELS, RECORDING_AUDIO_BITRATE_BPS,
            object : EncoderOutput {
                override fun onOutputFormat(format: MediaFormat) {
                    try {
                        output.onConfig(audioTrackConfig(format))
                    } catch (error: Exception) {
                        output.onError(error)
                    }
                }

                override fun onPacket(packet: EncodedPacket) = output.onPacket(packet)

                override fun onEndOfStream() = output.onEndOfStream()

                override fun onError(error: Exception) = output.onError(error)
            }, name = "recording-audio", maxPendingChunks = MAX_RECORDING_AUDIO_PENDING_CHUNKS)
        try {
            encoder.start()
        } catch (error: Exception) {
            encoder.close()
            throw error
        }
        RecordingLog.i("audio_stream_started rate=$RECORDING_AUDIO_SAMPLE_RATE channels=$RECORDING_AUDIO_CHANNELS")
        return object : RecordingAudioEncoder {
            override fun queuePcm(data: ByteArray, size: Int, presentationTimeUs: Long) =
                encoder.queuePcm(data, size, presentationTimeUs)

            override fun queueSilence(size: Int, presentationTimeUs: Long) = encoder.queueSilence(size, presentationTimeUs)

            override fun signalEndOfInput() = encoder.signalEndOfInput()

            override fun close() = encoder.close()
        }
    }

    private companion object {
        /** 음성 입력이 10ms 단위이므로 약 60초를 보관합니다. 무음 채움은 데이터 없이 표시만 합니다. */
        const val MAX_RECORDING_AUDIO_PENDING_CHUNKS = 6_000
    }
}

private fun MediaFormat.bytes(key: String): ByteArray? = getByteBuffer(key)?.let { buffer ->
    ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
}

internal fun videoTrackConfig(format: MediaFormat, fallback: RecordingSize): VideoTrackConfig {
    val csd = listOfNotNull(format.bytes("csd-0"), format.bytes("csd-1"))
    val (sps, pps) = Mp4Codec.parameterSets(*csd.toTypedArray())
    val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else fallback.width
    val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else fallback.height
    return VideoTrackConfig(width, height, sps, pps)
}

internal fun audioTrackConfig(format: MediaFormat): AudioTrackConfig = AudioTrackConfig(
    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
    audioSpecificConfig = checkNotNull(format.bytes("csd-0")) { "AAC 설정이 없습니다." },
)

/**
 * 녹화 영상용 H.264 인코더입니다. 입력 Surface를 받으며 [signalEndOfInputStream] 뒤 마지막 출력에서
 * [EncoderOutput.onEndOfStream]이 옵니다. 카메라가 멈춘 동안에도 마지막 프레임을 반복해 시간축을 유지합니다.
 */
internal class RecordingVideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrateBps: Int,
    output: EncoderOutput,
    codecFactory: (String) -> MediaCodec = MediaCodec::createEncoderByType,
) : AsyncMediaEncoder("recording-video", output, codecFactory) {
    lateinit var inputSurface: Surface
        private set

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps * 2)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        initializeOrClose {
            try {
                configure(format, MediaFormat.MIMETYPE_VIDEO_AVC, surfaceInput = true)
            } catch (_: Exception) {
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

    /** 더 이상 Surface에 그리지 않을 때 부릅니다. 인코더 스레드에서 종료 상태 예외는 무시합니다. */
    fun signalEndOfInputStream() {
        handler.post { if (!isReleased) runCatching { codec.signalEndOfInputStream() } }
    }
}
