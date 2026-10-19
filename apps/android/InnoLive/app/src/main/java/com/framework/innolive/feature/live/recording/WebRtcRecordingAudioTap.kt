package com.framework.innolive.feature.live.recording

import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WebRTC 음성 처리 모듈의 캡처 후처리 단계에서 송출 직전 신호(에코 제거·잡음 억제 뒤)를 복사합니다
 * (iOS `WebRTCRecordingAudioTap`의 `capturePostProcessingDelegate`와 같은 위치). 버퍼는 읽기만 하므로
 * 송출 음성은 바뀌지 않습니다.
 *
 * 버퍼는 10ms 단위의 FloatS16(채널 0)입니다. 처리 샘플레이트는 [numFrames] × 100이며, 녹화기는 이를
 * 48kHz 스테레오로 바꿉니다. 오디오 처리 스레드에서 부르므로 할당 없이 재사용 버퍼에 복사합니다.
 */
internal class WebRtcRecordingAudioTap(
    private val nowUs: () -> Long = { System.nanoTime() / 1_000L },
) : ExternalAudioProcessingFactory.AudioProcessing {
    @Volatile var sink: RecordingAudioSink? = null
    private var scratch = ByteArray(0)

    override fun initialize(sampleRateHz: Int, numChannels: Int) = Unit

    override fun reset(newRate: Int) = Unit

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        val target = sink ?: return
        if (numFrames <= 0) return
        val floats = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = minOf(numFrames, floats.remaining())
        if (frames <= 0) return
        if (scratch.size < frames * 2) scratch = ByteArray(frames * 2)
        floatS16ToPcm16(floats, frames, scratch)
        val sampleRate = numFrames * 100
        // 콜백에는 캡처 시각이 없어 도착 시각에서 청크 길이를 뺀 값을 첫 샘플 시각으로 씁니다.
        val durationUs = frames * 1_000_000L / sampleRate
        target.onPcm(scratch, frames * 2, sampleRate, 1, nowUs() - durationUs)
    }
}
