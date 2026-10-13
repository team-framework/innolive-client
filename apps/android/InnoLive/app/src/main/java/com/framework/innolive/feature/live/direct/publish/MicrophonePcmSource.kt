package com.framework.innolive.feature.live.direct.publish

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission

/** PCM 16비트 청크와 단조 시계 기준 시각(µs)을 받습니다. */
internal fun interface PcmSink {
    fun onPcm(data: ByteArray, size: Int, presentationTimeUs: Long)
}

/** 직접 송출의 음성 입력입니다. 테스트에서는 합성 음원으로 바꿉니다. */
internal interface PcmSource : AutoCloseable {
    fun start(sink: PcmSink)
}

/**
 * 마이크를 직접 읽습니다. 직접 송출 세션에는 WebRTC 오디오 모듈이 없으므로 AudioRecord를
 * 따로 엽니다. 방송 음성이므로 통화용 에코 제거 경로(VOICE_COMMUNICATION) 대신 일반 마이크를 씁니다.
 */
internal class MicrophonePcmSource(
    private val profile: DirectPublishProfile,
    private val preferredDevice: AudioDeviceInfo? = null,
    private val nowNs: () -> Long = System::nanoTime,
    private val onError: (Exception) -> Unit = {},
) : PcmSource {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(sink: PcmSink) {
        check(!running) { "이미 마이크를 읽고 있습니다." }
        val channelMask = if (profile.isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val bytesPerFrame = 2 * profile.audioChannels
        // 10ms 단위로 읽어 음성 지연을 짧게 둡니다.
        val chunkBytes = profile.audioSampleRate / 100 * bytesPerFrame
        val minBuffer = AudioRecord.getMinBufferSize(profile.audioSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "마이크 형식을 지원하지 않습니다." }
        val created = AudioRecord(MediaRecorder.AudioSource.MIC, profile.audioSampleRate, channelMask,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunkBytes * 4))
        check(created.state == AudioRecord.STATE_INITIALIZED) {
            created.release()
            "마이크를 열지 못했습니다."
        }
        preferredDevice?.let(created::setPreferredDevice)
        record = created
        running = true
        created.startRecording()
        thread = Thread({
            val buffer = ByteArray(chunkBytes)
            val bytesPerSecond = profile.audioSampleRate.toLong() * bytesPerFrame
            try {
                while (running) {
                    val read = created.read(buffer, 0, buffer.size)
                    if (read < 0) throw IllegalStateException("마이크 읽기 실패: $read")
                    if (read == 0) continue
                    // 방금 읽은 청크의 첫 샘플 시각입니다.
                    val durationUs = read * 1_000_000L / bytesPerSecond
                    sink.onPcm(buffer, read, nowNs() / 1000 - durationUs)
                }
            } catch (error: Exception) {
                if (running) onError(error)
            }
        }, "direct-publish-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun close() {
        running = false
        runCatching { record?.stop() }
        thread?.join(500)
        runCatching { record?.release() }
        record = null
        thread = null
    }
}
