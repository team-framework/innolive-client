package com.framework.innolive.feature.live.direct.publish

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executor
import kotlin.math.abs

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
    /** 실제 녹음 장치와 차단(silenced) 여부입니다. 다른 앱이 마이크를 가져가면 silenced로 옵니다. */
    private val onRouteChanged: (deviceId: Int?, silenced: Boolean) -> Unit = { _, _ -> },
) : PcmSource {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var routeCallback: AudioManager.AudioRecordingCallback? = null

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
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == created.audioSessionId }?.let(::reportRoute)
            }
        }
        created.registerAudioRecordingCallback(callbackExecutor, callback)
        routeCallback = callback
        created.startRecording()
        created.activeRecordingConfiguration?.let(::reportRoute)
        thread = Thread({
            val buffer = ByteArray(chunkBytes)
            val clock = PcmClock(profile.audioSampleRate)
            try {
                while (running) {
                    val read = created.read(buffer, 0, buffer.size)
                    if (read < 0) throw IllegalStateException("마이크 읽기 실패: $read")
                    if (read == 0) continue
                    sink.onPcm(buffer, read, clock.next(read / bytesPerFrame, nowNs() / 1000))
                }
            } catch (error: Exception) {
                if (running) onError(error)
            }
        }, "direct-publish-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun reportRoute(config: AudioRecordingConfiguration) {
        if (running) onRouteChanged(config.audioDevice?.id, config.isClientSilenced)
    }

    override fun close() {
        running = false
        routeCallback?.let { callback -> runCatching { record?.unregisterAudioRecordingCallback(callback) } }
        routeCallback = null
        runCatching { record?.stop() }
        thread?.join(500)
        runCatching { record?.release() }
        record = null
        thread = null
    }
}

/**
 * 마이크 청크의 첫 샘플 시각을 샘플 수로 이어 붙입니다.
 *
 * 마이크는 HAL 주기(S25는 20ms)마다 데이터를 몰아서 넘기므로 10ms 청크를 연달아 읽으면 읽은 시각이
 * 거의 같습니다. 읽은 시각으로 청크 시각을 정하면 [DirectAudioInput]이 뒤 청크를 겹침으로 보고 버립니다.
 * 샘플이 실제로 빠졌거나 시계가 벌어져 읽은 시각과 [resyncUs] 넘게 차이 나면 읽은 시각에 다시 맞춥니다.
 */
internal class PcmClock(
    private val sampleRate: Int,
    private val resyncUs: Long = 100_000L,
) {
    private var anchorUs = 0L
    private var frames = -1L

    /** [readEndUs]는 [frameCount] 프레임을 읽은 직후의 단조 시각(µs)입니다. 청크 첫 샘플 시각을 돌려줍니다. */
    fun next(frameCount: Int, readEndUs: Long): Long {
        val readStartUs = readEndUs - frameCount * 1_000_000L / sampleRate
        var timeUs = anchorUs + frames * 1_000_000L / sampleRate
        if (frames < 0 || abs(readStartUs - timeUs) > resyncUs) {
            anchorUs = readStartUs
            frames = 0
            timeUs = readStartUs
        }
        frames += frameCount
        return timeUs
    }
}

/** 녹음 경로 콜백은 바로 실행합니다. 받는 쪽이 자기 스레드로 넘깁니다. */
private val callbackExecutor = Executor { it.run() }
