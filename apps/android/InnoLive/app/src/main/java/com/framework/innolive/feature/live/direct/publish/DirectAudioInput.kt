package com.framework.innolive.feature.live.direct.publish

import android.media.AudioDeviceInfo
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 마이크 입력 상태입니다. [MicrophoneState.SILENCED]는 다른 앱이나 시스템이 녹음을 막은 상태입니다. */
internal enum class MicrophoneState { OK, UNAVAILABLE, SILENCED }

/** 마이크를 여는 방법입니다. 오류와 실제 녹음 경로는 콜백으로 알립니다. */
internal fun interface MicrophoneFactory {
    fun open(
        device: AudioDeviceInfo?,
        onError: (Exception) -> Unit,
        onRouteChanged: (deviceId: Int?, silenced: Boolean) -> Unit,
    ): PcmSource
}

/**
 * 직접 송출의 음성 입력입니다. YouTube는 음성 트랙이 없으면 라이브 전환을 거부하므로 마이크가 없거나
 * 끊긴 구간은 무음으로 채워 음성이 계속 나가게 합니다(iOS `DirectMediaFanout`과 같음).
 *
 * - [setMuted]가 켜져 있으면 마이크 음성 대신 같은 길이의 무음을 보냅니다(대기 화면, 사생활 보호).
 * - 시각은 항상 증가합니다. 무음으로 채운 구간과 겹치는 마이크 청크는 버리거나 뒤로 미룹니다.
 */
internal class DirectAudioInput(
    private val profile: DirectPublishProfile,
    private val microphone: MicrophoneFactory,
    private var preferredDevice: AudioDeviceInfo?,
    private val onState: (MicrophoneState) -> Unit,
    private val nowUs: () -> Long = { System.nanoTime() / 1000 },
    /** 무음 채우기 주기입니다. 테스트는 null로 두고 [fillSilence]를 직접 부릅니다. */
    private val keeperFactory: (() -> ScheduledExecutorService)? = {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "direct-publish-audio-keeper") }
    },
) : PcmSource {
    private val lock = Any()
    private var sink: PcmSink? = null
    private var mic: PcmSource? = null
    private var micGeneration = 0
    private var keeper: ScheduledExecutorService? = null
    private var coveredUntilUs = 0L
    private var muted = false
    private var closed = false
    private var state: MicrophoneState? = null
    private val bytesPerSecond = profile.audioSampleRate.toLong() * 2 * profile.audioChannels
    private val chunkBytes = (profile.audioSampleRate / 100) * 2 * profile.audioChannels
    private val silence = ByteArray(chunkBytes)

    override fun start(sink: PcmSink) {
        synchronized(lock) {
            check(this.sink == null && !closed) { "이미 시작한 음성 입력입니다." }
            this.sink = sink
            coveredUntilUs = nowUs()
        }
        openMicrophone()
        keeperFactory?.invoke()?.also { executor ->
            synchronized(lock) { keeper = executor }
            executor.scheduleWithFixedDelay(::fillSilence, KEEPER_INTERVAL_MS, KEEPER_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun setMuted(value: Boolean) = synchronized(lock) { muted = value }

    /** 사용자가 고른 입력 장치로 마이크를 다시 엽니다. 그 사이는 무음으로 채웁니다. */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        synchronized(lock) {
            if (closed || sink == null) {
                preferredDevice = device
                return
            }
            preferredDevice = device
        }
        closeMicrophone()
        openMicrophone()
    }

    private fun openMicrophone() {
        val (generation, device) = synchronized(lock) {
            if (closed) return
            ++micGeneration to preferredDevice
        }
        val opened = try {
            microphone.open(device,
                onError = { onMicrophoneError(generation) },
                onRouteChanged = { _, silenced ->
                    if (isCurrent(generation)) report(if (silenced) MicrophoneState.SILENCED else MicrophoneState.OK)
                })
        } catch (_: Exception) {
            report(MicrophoneState.UNAVAILABLE)
            return
        }
        try {
            opened.start { data, size, presentationTimeUs -> onMicrophonePcm(generation, data, size, presentationTimeUs) }
        } catch (_: Exception) {
            runCatching { opened.close() }
            report(MicrophoneState.UNAVAILABLE)
            return
        }
        val stale = synchronized(lock) {
            if (closed || generation != micGeneration) true else { mic = opened; false }
        }
        if (stale) runCatching { opened.close() } else report(MicrophoneState.OK)
    }

    private fun onMicrophoneError(generation: Int) {
        if (!isCurrent(generation)) return
        closeMicrophone()
        report(MicrophoneState.UNAVAILABLE)
    }

    private fun isCurrent(generation: Int) = synchronized(lock) { !closed && generation == micGeneration }

    private fun closeMicrophone() {
        val current = synchronized(lock) {
            micGeneration++
            mic.also { mic = null }
        }
        runCatching { current?.close() }
    }

    private fun onMicrophonePcm(generation: Int, data: ByteArray, size: Int, presentationTimeUs: Long) {
        synchronized(lock) {
            val target = sink ?: return
            if (closed || generation != micGeneration || size <= 0) return
            val durationUs = size * 1_000_000L / bytesPerSecond
            if (presentationTimeUs + durationUs <= coveredUntilUs) return
            val timeUs = maxOf(presentationTimeUs, coveredUntilUs)
            if (muted) target.onPcm(ByteArray(size), size, timeUs) else target.onPcm(data, size, timeUs)
            coveredUntilUs = timeUs + durationUs
        }
    }

    /** 마이크 음성이 [STALE_US] 넘게 끊겼으면 지금까지를 무음으로 채웁니다. */
    fun fillSilence() {
        synchronized(lock) {
            val target = sink ?: return
            if (closed) return
            val now = nowUs()
            if (now - coveredUntilUs < STALE_US) return
            val chunkUs = chunkBytes * 1_000_000L / bytesPerSecond
            // 오래 멈췄다면 지난 구간을 한꺼번에 보내지 않고 최근 구간만 채웁니다.
            if (now - coveredUntilUs > MAX_BACKFILL_US) coveredUntilUs = now - MAX_BACKFILL_US
            while (coveredUntilUs + chunkUs <= now) {
                target.onPcm(silence, silence.size, coveredUntilUs)
                coveredUntilUs += chunkUs
            }
        }
    }

    private fun report(next: MicrophoneState) {
        val changed = synchronized(lock) {
            if (closed || state == next) false else { state = next; true }
        }
        if (changed) onState(next)
    }

    override fun close() {
        val executor = synchronized(lock) {
            closed = true
            sink = null
            keeper.also { keeper = null }
        }
        executor?.shutdownNow()
        val current = synchronized(lock) { mic.also { mic = null } }
        runCatching { current?.close() }
    }

    private companion object {
        const val KEEPER_INTERVAL_MS = 50L
        const val STALE_US = 300_000L
        const val MAX_BACKFILL_US = 1_000_000L
    }
}
