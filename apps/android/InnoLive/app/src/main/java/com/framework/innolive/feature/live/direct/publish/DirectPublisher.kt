package com.framework.innolive.feature.live.direct.publish

import android.os.SystemClock
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.IngestUrl
import org.webrtc.EglBase
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 앱에서 YouTube·치지직으로 직접 송출하는 엔진입니다(contracts/api/direct-publish-v1.md).
 *
 * 가림 처리가 끝난 프레임만 [frameSink]로 받습니다. 원본 카메라 프레임을 받는 입력은 없으므로
 * 가림 처리에 실패한 프레임은 송출되지 않습니다. 영상·음성은 한 번 인코딩해 연결된 모든
 * 플랫폼으로 나눠 보냅니다. 재연결 정책과 서버 API 호출은 호출자가 맡습니다.
 */
internal class DirectPublisher(
    val profile: DirectPublishProfile,
    private val sharedEglContext: EglBase.Context,
    private val audioSource: PcmSource,
    private val listener: Listener,
    private val targetFactory: (BroadcastProvider, IngestUrl, (RtmpPublishTarget) -> Unit) -> RtmpPublishTarget =
        { provider, url, onChanged -> RtmpPublishTarget(provider, url, profile, onChanged) },
    private val nowNs: () -> Long = System::nanoTime,
    private val realtimeNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) : AutoCloseable {
    interface Listener {
        /** RTMP 연결 상태가 바뀌었습니다. 메인 스레드 또는 RTMP 스레드에서 옵니다. */
        fun onTargetStateChanged(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure?) = Unit

        /** 인코더·마이크·렌더러가 멈췄습니다. 송출을 이어 갈 수 없으므로 모든 연결을 정리합니다. */
        fun onPublisherFailed(error: Exception)
    }

    private val lock = Any()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val targets = linkedMapOf<BroadcastProvider, RtmpPublishTarget>()
    private val droppedFrames = AtomicLong()
    private val frameRate = FrameRateMeter(nowNs)
    private val lastVideoPtsNs = AtomicLong(Long.MIN_VALUE)
    private val keyFrames = KeyFrameScheduler(profile.keyFrameIntervalSeconds * 1_000_000L)
    private var videoEncoder: H264SurfaceEncoder? = null
    private var audioEncoder: AacEncoder? = null
    private var renderer: EncoderSurfaceRenderer? = null
    private lateinit var fanout: PacketFanout

    /** 가림 처리된 프레임 입력입니다. [start] 전과 [close] 뒤의 프레임은 버립니다. */
    val frameSink: VideoSink = VideoSink(::onProtectedFrame)

    /** 인코더와 마이크를 켭니다. 플랫폼 연결 전에 불러 첫 연결이 SPS·PPS를 바로 받게 합니다. */
    fun start() {
        check(!closed.get()) { "이미 종료된 송출 엔진입니다." }
        if (!started.compareAndSet(false, true)) return
        try {
            val video = H264SurfaceEncoder(profile, encoderOutput(isVideo = true))
            videoEncoder = video
            fanout = PacketFanout(video::requestKeyFrame)
            renderer = EncoderSurfaceRenderer(sharedEglContext, video.inputSurface, profile.width, profile.height,
                onRendered = frameRate::onFrame,
                onDropped = { droppedFrames.incrementAndGet() },
                onError = ::fail)
            val audio = AacEncoder(profile, encoderOutput(isVideo = false))
            audioEncoder = audio
            video.start()
            audio.start()
            audioSource.start(audio::queuePcm)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /** 플랫폼 연결을 엽니다. 같은 플랫폼의 기존 연결은 닫고 새 주소로 바꿉니다. */
    fun connect(provider: BroadcastProvider, ingestUrl: IngestUrl) {
        check(started.get() && !closed.get()) { "송출 엔진이 시작되지 않았습니다." }
        val target = targetFactory(provider, ingestUrl) { changed ->
            listener.onTargetStateChanged(changed.provider, changed.currentState, changed.lastFailure)
        }
        val previous = synchronized(lock) { targets.put(provider, target) }
        previous?.disconnect()
        fanout.add(provider, target)
        target.connect()
    }

    /** 끊긴 연결을 같은 주소로 다시 엽니다. 연결 중이거나 송출 중이면 아무것도 하지 않습니다. */
    fun reconnect(provider: BroadcastProvider) {
        synchronized(lock) { targets[provider] }?.connect()
    }

    fun disconnect(provider: BroadcastProvider) {
        val target = synchronized(lock) { targets.remove(provider) } ?: return
        if (::fanout.isInitialized) fanout.remove(provider)
        target.disconnect()
    }

    fun providers(): List<BroadcastProvider> = synchronized(lock) { targets.keys.toList() }

    fun stats(): DirectPublishStats = DirectPublishStats(
        targets = synchronized(lock) { targets.values.map(RtmpPublishTarget::stats) },
        fps = frameRate.sample(),
        droppedFrames = droppedFrames.get(),
        width = profile.width,
        height = profile.height,
    )

    private fun onProtectedFrame(frame: VideoFrame) {
        if (!started.get() || closed.get() || failed.get()) return
        val renderer = renderer ?: return
        val monotonicNs = cameraTimestampToMonotonicNs(frame.timestampNs, nowNs(), realtimeNs())
        // 인코더는 증가하는 시각만 받습니다. 시계 판정이 바뀌어 되돌아간 프레임은 버립니다.
        val previous = lastVideoPtsNs.get()
        if (monotonicNs <= previous || !lastVideoPtsNs.compareAndSet(previous, monotonicNs)) {
            droppedFrames.incrementAndGet()
            return
        }
        renderer.render(frame, monotonicNs)
    }

    private fun encoderOutput(isVideo: Boolean) = object : EncoderOutput {
        override fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer) = fanout.onVideoConfig(sps, pps)

        override fun onPacket(packet: EncodedPacket) {
            if (!isVideo) return fanout.onAudio(packet)
            // 영상 출력 스레드에서만 부르므로 스케줄러는 잠금 없이 씁니다.
            if (keyFrames.onVideoPacket(packet.presentationTimeUs, packet.isKeyFrame)) videoEncoder?.requestKeyFrame()
            fanout.onVideo(packet)
        }

        override fun onError(error: Exception) = fail(error)
    }

    private fun fail(error: Exception) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        listener.onPublisherFailed(error)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = synchronized(lock) { targets.values.toList().also { targets.clear() } }
        active.forEach(RtmpPublishTarget::disconnect)
        runCatching { audioSource.close() }
        runCatching { renderer?.close() }
        runCatching { videoEncoder?.close() }
        runCatching { audioEncoder?.close() }
        renderer = null
        videoEncoder = null
        audioEncoder = null
    }
}
