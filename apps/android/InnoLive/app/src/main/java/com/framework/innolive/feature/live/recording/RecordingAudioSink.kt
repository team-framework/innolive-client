package com.framework.innolive.feature.live.recording

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 송출 경로가 녹화기에 넘기는 송출 처리 후 PCM 16비트(인터리브)입니다. 서버 중계는 WebRTC 음성 처리
 * (에코 제거·잡음 억제) 뒤의 캡처 신호, 직접 송출은 엔진에 넣는 PCM(무음 채움·음소거 포함)입니다.
 * [presentationTimeUs]는 `System.nanoTime` 기준 첫 샘플 시각입니다. 캡처 스레드에서 바로 부르므로
 * [data]는 호출 안에서 복사해야 합니다. 녹화기는 형식과 관계없이 48kHz 스테레오로 바꿔 저장합니다.
 */
internal fun interface RecordingAudioSink {
    fun onPcm(data: ByteArray, size: Int, sampleRate: Int, channels: Int, presentationTimeUs: Long)
}

/** 녹화 음성은 iOS와 같이 48kHz 스테레오로 고정합니다. */
internal const val RECORDING_AUDIO_SAMPLE_RATE = 48_000
internal const val RECORDING_AUDIO_CHANNELS = 2

/**
 * 콜백 도착 시각 대신 누적 샘플 수로 음성 시간축을 만듭니다. 도착 시각은 스레드 지연으로 흔들리지만
 * 샘플 수는 캡처 장치의 시계를 따르기 때문입니다. 캡처가 [resyncThresholdUs] 넘게 끊기거나 앞서면
 * 실제 공백으로 보고 도착 시각에 다시 맞춥니다(iOS `WebRTCRecordingAudioTap`과 같음).
 */
internal class RecordingAudioTimeline(
    private val sampleRate: Int,
    private val resyncThresholdUs: Long = 250_000L,
) {
    private var anchorUs: Long? = null
    private var frames = 0L

    /** 이번 청크의 시각을 돌려주고 [frameCount]만큼 시간축을 진행합니다. */
    fun next(arrivalUs: Long, frameCount: Int): Long {
        val anchor = anchorUs
        var expected = if (anchor == null) arrivalUs else anchor + frames * 1_000_000L / sampleRate
        if (anchor == null || abs(arrivalUs - expected) > resyncThresholdUs) {
            anchorUs = maxOf(arrivalUs, if (anchor == null) arrivalUs else expected)
            frames = 0
            expected = checkNotNull(anchorUs)
        }
        frames += frameCount
        return expected
    }
}

/**
 * 인코더에 넣을 음성 위치를 정합니다. 시간축([RecordingAudioTimeline])이 정한 청크 시각과 지금까지 쓴 끝
 * 사이가 [toleranceUs]보다 벌어지면 그만큼 실제 무음을 넣고, 겹치면 겹친 앞부분을 잘라 냅니다(iOS
 * `LocalBroadcastRecorder.pumpAudio`와 같음). 시각은 쓴 프레임 수로 계산하므로 인코더 입력이 끊기지 않습니다.
 */
internal class RecordingAudioClock(
    private val sampleRate: Int = RECORDING_AUDIO_SAMPLE_RATE,
    resyncThresholdUs: Long = 250_000L,
    private val toleranceUs: Long = 3_000L,
) {
    /** [silenceFrames]만큼 무음을 [silenceStartFrame]부터 쓰고, 청크 앞 [skipFrames]를 버린 나머지를 [chunkStartFrame]부터 씁니다. */
    data class Placement(val silenceStartFrame: Long, val silenceFrames: Long, val skipFrames: Int, val chunkStartFrame: Long)

    private val timeline = RecordingAudioTimeline(sampleRate, resyncThresholdUs)
    private var baseUs: Long? = null
    private var writtenFrames = 0L

    val framesWritten: Long get() = writtenFrames

    fun timeOfFrame(frame: Long): Long = checkNotNull(baseUs) + frame * 1_000_000L / sampleRate

    fun place(arrivalUs: Long, frameCount: Int): Placement {
        val chunkUs = timeline.next(arrivalUs, frameCount)
        val base = baseUs ?: chunkUs.also { baseUs = it }
        val coveredUs = base + writtenFrames * 1_000_000L / sampleRate
        val delta = chunkUs - coveredUs
        val silenceStart = writtenFrames
        var silence = 0L
        var skip = 0
        if (delta > toleranceUs) {
            silence = (delta * sampleRate / 1_000_000.0).roundToLong()
            writtenFrames += silence
        } else if (delta < -toleranceUs) {
            skip = minOf(frameCount.toLong(), (-delta * sampleRate / 1_000_000.0).roundToLong()).toInt()
        }
        val chunkStart = writtenFrames
        writtenFrames += frameCount - skip
        return Placement(silenceStart, silence, skip, chunkStart)
    }
}

/**
 * PCM 16비트 인터리브 입력을 48kHz 스테레오 PCM 16비트로 바꿉니다. 모노는 두 채널에 복사하고 다른
 * 샘플레이트는 청크 경계를 이어 가며 선형 보간합니다. 형식이 바뀌면 보간 상태를 새로 시작합니다.
 */
internal class RecordingAudioConverter(private val outputRate: Int = RECORDING_AUDIO_SAMPLE_RATE) {
    private var inputRate = 0
    private var inputChannels = 0
    /** 다음 출력 샘플의 입력 위치입니다. -1과 0 사이면 이전 청크의 마지막 프레임과 보간합니다. */
    private var position = 0.0
    private var lastLeft = 0
    private var lastRight = 0

    /** 바뀐 스테레오 PCM(인터리브 short)을 돌려줍니다. 형식이 바뀌었으면 [formatChanged]가 true입니다. */
    var formatChanged = false
        private set

    fun convert(data: ByteArray, size: Int, sampleRate: Int, channels: Int): ShortArray {
        require(sampleRate > 0 && channels in 1..2)
        formatChanged = sampleRate != inputRate || channels != inputChannels
        if (formatChanged) {
            inputRate = sampleRate
            inputChannels = channels
            position = 0.0
            lastLeft = 0
            lastRight = 0
        }
        val frames = size / (2 * channels)
        if (frames <= 0) return ShortArray(0)
        fun sample(frame: Int, channel: Int): Int {
            val index = (frame * channels + minOf(channel, channels - 1)) * 2
            return (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
        }
        if (sampleRate == outputRate) {
            val output = ShortArray(frames * 2)
            for (frame in 0 until frames) {
                output[frame * 2] = sample(frame, 0).toShort()
                output[frame * 2 + 1] = sample(frame, 1).toShort()
            }
            lastLeft = sample(frames - 1, 0)
            lastRight = sample(frames - 1, 1)
            return output
        }
        val step = sampleRate.toDouble() / outputRate
        val output = ShortArrayBuilder((frames / step).toInt() + 4)
        while (position < frames - 1) {
            val index = kotlin.math.floor(position).toInt()
            val fraction = position - index
            val left0 = if (index < 0) lastLeft else sample(index, 0)
            val right0 = if (index < 0) lastRight else sample(index, 1)
            val left1 = sample(index + 1, 0)
            val right1 = sample(index + 1, 1)
            output.add((left0 + (left1 - left0) * fraction).toInt().toShort())
            output.add((right0 + (right1 - right0) * fraction).toInt().toShort())
            position += step
        }
        lastLeft = sample(frames - 1, 0)
        lastRight = sample(frames - 1, 1)
        position -= frames
        return output.build()
    }

    private class ShortArrayBuilder(capacity: Int) {
        private var values = ShortArray(maxOf(capacity * 2, 2))
        private var size = 0
        fun add(value: Short) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }
        fun build(): ShortArray = values.copyOf(size)
    }
}

/** WebRTC 음성 처리 버퍼(FloatS16, -32768..32767)를 PCM 16비트 리틀 엔디언으로 바꿉니다. */
internal fun floatS16ToPcm16(source: java.nio.FloatBuffer, frames: Int, target: ByteArray) {
    for (index in 0 until frames) {
        val value = source.get(index)
        val sample = if (value.isFinite()) value.toInt().coerceIn(-32_768, 32_767) else 0
        target[index * 2] = sample.toByte()
        target[index * 2 + 1] = (sample shr 8).toByte()
    }
}
