package com.framework.innolive.feature.live.direct.publish

import android.os.SystemClock
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.IngestUrl
import org.webrtc.EglBase
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 상태 머신이 쓰는 송출 엔진 동작입니다. 실제 구현은 [DirectPublisher]이고 테스트는 가짜 엔진을 씁니다. */
internal interface DirectPublishEngine : AutoCloseable {
    val profile: DirectPublishProfile
    fun connect(provider: BroadcastProvider, ingestUrl: IngestUrl)
    fun reconnect(provider: BroadcastProvider)
    fun disconnect(provider: BroadcastProvider)

    /** 켜져 있으면 카메라 프레임 대신 대기 화면을, 음성 대신 무음을 보냅니다. 연결은 유지합니다. */
    fun setSlate(active: Boolean)
    fun stats(): DirectPublishStats
}

/**
 * 앱에서 YouTube·치지직으로 직접 송출하는 엔진입니다(contracts/api/direct-publish-v1.md).
 *
 * 가림 처리가 끝난 프레임만 [frameSink]로 받습니다. 원본 카메라 프레임을 받는 입력은 없으므로
 * 가림 처리에 실패한 프레임은 송출되지 않습니다. 영상·음성은 한 번 인코딩해 연결된 모든
 * 플랫폼으로 나눠 보냅니다. 재연결 정책과 서버 API 호출은 호출자가 맡습니다.
 */
internal class DirectPublisher(
    override val profile: DirectPublishProfile,
    private val sharedEglContext: EglBase.Context,
    private val audioSource: PcmSource,
    private val listener: Listener,
    /** 대기 화면입니다. null이면 검은 화면을 보냅니다. */
    private val slateSource: SlateSource? = null,
    private val targetFactory: (BroadcastProvider, IngestUrl, (RtmpPublishTarget) -> Unit) -> RtmpPublishTarget =
        { provider, url, onChanged -> RtmpPublishTarget(provider, url, profile, onChanged) },
    private val nowNs: () -> Long = System::nanoTime,
    private val realtimeNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) : DirectPublishEngine {
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
    private val slateActive = AtomicBoolean(false)
    private var slateTicker: ScheduledExecutorService? = null

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
            // 첫 연결이 영상 설정을 바로 받도록 검은 화면 한 장을 넣습니다. 음성은 입력이 무음으로 채웁니다.
            // 인코더가 마지막 프레임을 반복하므로 카메라 프레임 전에도 영상이 이어집니다.
            renderOwnFrame(blackI420Buffer(profile.width, profile.height))
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun setSlate(active: Boolean) {
        if (closed.get() || slateActive.getAndSet(active) == active) return
        (audioSource as? DirectAudioInput)?.setMuted(active)
        synchronized(lock) {
            if (active) {
                slateTicker = Executors.newSingleThreadScheduledExecutor { Thread(it, "direct-publish-slate") }.also {
                    it.scheduleWithFixedDelay(::renderSlate, 0, 1000L / SLATE_FPS, TimeUnit.MILLISECONDS)
                }
            } else {
                slateTicker?.shutdownNow()
                slateTicker = null
            }
        }
    }

    private fun renderSlate() {
        if (!slateActive.get() || closed.get()) return
        val buffer = try {
            slateSource?.buffer(profile.width, profile.height)?.also { it.retain() }
                ?: blackI420Buffer(profile.width, profile.height)
        } catch (_: Exception) {
            blackI420Buffer(profile.width, profile.height)
        }
        renderOwnFrame(buffer)
    }

    /** 앱이 만든 프레임(검은 화면·대기 화면)을 지금 시각으로 그립니다. [buffer] 참조는 여기서 돌려줍니다. */
    private fun renderOwnFrame(buffer: VideoFrame.Buffer) {
        val timeNs = nowNs()
        val frame = VideoFrame(buffer, 0, timeNs)
        try {
            if (!started.get() || closed.get() || failed.get()) return
            val renderer = renderer ?: return
            if (advancePts(timeNs)) renderer.render(frame, timeNs)
        } finally {
            frame.release()
        }
    }

    /** 인코더는 증가하는 시각만 받습니다. 시각이 되돌아간 프레임은 버립니다. */
    private fun advancePts(monotonicNs: Long): Boolean {
        val previous = lastVideoPtsNs.get()
        if (monotonicNs <= previous || !lastVideoPtsNs.compareAndSet(previous, monotonicNs)) {
            droppedFrames.incrementAndGet()
            return false
        }
        return true
    }

    /** 플랫폼 연결을 엽니다. 같은 플랫폼의 기존 연결은 닫고 새 주소로 바꿉니다. */
    override fun connect(provider: BroadcastProvider, ingestUrl: IngestUrl) {
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
    override fun reconnect(provider: BroadcastProvider) {
        synchronized(lock) { targets[provider] }?.connect()
    }

    override fun disconnect(provider: BroadcastProvider) {
        val target = synchronized(lock) { targets.remove(provider) } ?: return
        if (::fanout.isInitialized) fanout.remove(provider)
        target.disconnect()
    }

    fun providers(): List<BroadcastProvider> = synchronized(lock) { targets.keys.toList() }

    override fun stats(): DirectPublishStats = DirectPublishStats(
        targets = synchronized(lock) { targets.values.map(RtmpPublishTarget::stats) },
        fps = frameRate.sample(),
        droppedFrames = droppedFrames.get(),
        width = profile.width,
        height = profile.height,
    )

    private fun onProtectedFrame(frame: VideoFrame) {
        // 대기 화면 중에는 카메라 프레임을 보내지 않습니다(일시 중지·백그라운드).
        if (!started.get() || closed.get() || failed.get() || slateActive.get()) return
        val renderer = renderer ?: return
        val monotonicNs = cameraTimestampToMonotonicNs(frame.timestampNs, nowNs(), realtimeNs())
        if (advancePts(monotonicNs)) renderer.render(frame, monotonicNs)
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
        synchronized(lock) {
            slateTicker?.shutdownNow()
            slateTicker = null
        }
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

/** 대기 화면 프레임 수입니다. iOS와 같습니다. */
private const val SLATE_FPS = 15
