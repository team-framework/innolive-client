package com.framework.innolive.feature.live.recording

/**
 * 송출 경로가 녹화기에 넘기는 마이크 PCM 16비트입니다. 서버 중계는 WebRTC 녹음 콜백, 직접 송출은
 * 송출 엔진에 넣는 PCM(무음 채움·음소거 포함)에서 옵니다. [presentationTimeUs]는 `System.nanoTime` 기준
 * 첫 샘플 시각입니다. 캡처 스레드에서 바로 부르므로 [data]는 호출 안에서 복사해야 합니다.
 */
internal fun interface RecordingAudioSink {
    fun onPcm(data: ByteArray, size: Int, sampleRate: Int, channels: Int, presentationTimeUs: Long)
}

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
        if (anchor == null || kotlin.math.abs(arrivalUs - expected) > resyncThresholdUs) {
            anchorUs = maxOf(arrivalUs, if (anchor == null) arrivalUs else expected)
            frames = 0
            expected = checkNotNull(anchorUs)
        }
        frames += frameCount
        return expected
    }
}
