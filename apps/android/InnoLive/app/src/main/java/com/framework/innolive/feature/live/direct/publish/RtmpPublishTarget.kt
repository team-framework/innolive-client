package com.framework.innolive.feature.live.direct.publish

import android.media.MediaCodec
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.IngestUrl
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.TrustManager

internal enum class RtmpTargetState { IDLE, CONNECTING, PUBLISHING, FAILED, DISCONNECTED }

/** 연결 실패 사유입니다. 라이브러리 원문에는 주소가 섞일 수 있어 분류만 남깁니다. */
internal enum class RtmpFailure { AUTH, REJECTED, NETWORK }

internal fun classifyRtmpFailure(reason: String): RtmpFailure = when {
    reason.contains("onStatus", ignoreCase = true) ||
        reason.contains("Endpoint malformed", ignoreCase = true) -> RtmpFailure.REJECTED
    else -> RtmpFailure.NETWORK
}

/**
 * 플랫폼 하나로 가는 RTMP(S) 연결입니다. ingest URL은 연결할 때만 꺼내며 상태·로그·예외에
 * 남기지 않습니다. 재연결 정책은 호출자가 [connect]를 다시 불러 정합니다.
 */
internal class RtmpPublishTarget(
    val provider: BroadcastProvider,
    private val ingestUrl: IngestUrl,
    private val profile: DirectPublishProfile,
    private val onStateChanged: (RtmpPublishTarget) -> Unit = {},
    /** 사설 인증서를 쓰는 검증용 RTMPS 서버에만 넘깁니다. null이면 시스템 신뢰 저장소를 씁니다. */
    private val trustManager: TrustManager? = null,
) : PublishEndpoint {
    private val state = AtomicReference(RtmpTargetState.IDLE)
    private val failure = AtomicReference<RtmpFailure?>(null)
    private val bitrateBps = AtomicLong(-1)
    private val connectAttempts = AtomicInteger(0)

    private val lock = Any()
    private var generation = 0
    private var client: RtmpClient? = null

    /** 시도마다 새 클라이언트를 씁니다. 라이브러리의 disconnect는 비동기라 같은 클라이언트로 재연결하면 새 연결이 취소될 수 있습니다. */
    private fun newClient(attempt: Int): RtmpClient = RtmpClient(object : ConnectChecker {
        // 라이브러리가 넘기는 url 인자는 받기만 하고 쓰지 않습니다.
        override fun onConnectionStarted(url: String) = Unit

        override fun onConnectionSuccess() = update(attempt, RtmpTargetState.PUBLISHING, null)

        override fun onConnectionFailed(reason: String) =
            update(attempt, RtmpTargetState.FAILED, classifyRtmpFailure(reason))

        override fun onDisconnect() {
            if (state.get() != RtmpTargetState.FAILED) update(attempt, RtmpTargetState.DISCONNECTED, null)
        }

        override fun onAuthError() = update(attempt, RtmpTargetState.FAILED, RtmpFailure.AUTH)

        override fun onAuthSuccess() = Unit

        override fun onNewBitrate(bitrate: Long) {
            if (isCurrent(attempt)) bitrateBps.set(bitrate)
        }
    }).apply {
        setVideoCodec(VideoCodec.H264)
        setAudioCodec(AudioCodec.AAC)
        setVideoResolution(profile.width, profile.height)
        setFps(profile.fps)
        setAudioInfo(profile.audioSampleRate, profile.isStereo)
        setLogs(false)
        trustManager?.let(::addCertificates)
    }

    val currentState: RtmpTargetState get() = state.get()
    val lastFailure: RtmpFailure? get() = failure.get()

    /** 첫 연결 이후의 연결 시도 횟수입니다. */
    val reconnects: Int get() = (connectAttempts.get() - 1).coerceAtLeast(0)

    override val isPublishing: Boolean get() = state.get() == RtmpTargetState.PUBLISHING

    fun connect() {
        val next = synchronized(lock) {
            val current = state.get()
            if (current == RtmpTargetState.CONNECTING || current == RtmpTargetState.PUBLISHING) return
            client?.disconnect()
            generation++
            connectAttempts.incrementAndGet()
            bitrateBps.set(-1)
            state.set(RtmpTargetState.CONNECTING)
            failure.set(null)
            newClient(generation).also { client = it }
        }
        onStateChanged(this)
        next.connect(ingestUrl.reveal())
    }

    fun disconnect() {
        synchronized(lock) {
            client?.disconnect()
            client = null
            generation++
            state.set(RtmpTargetState.DISCONNECTED)
            failure.set(null)
        }
        onStateChanged(this)
    }

    fun stats(): TargetPublishStats = TargetPublishStats(
        provider = provider,
        connected = isPublishing,
        bitrateKbps = bitrateBps.get().takeIf { it >= 0 && isPublishing }?.let { (it / 1000).toInt() },
        reconnects = reconnects,
    )

    override fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer) {
        currentClient()?.setVideoInfo(sps, pps, null)
    }

    override fun sendVideo(data: ByteBuffer, presentationTimeUs: Long, isKeyFrame: Boolean) {
        currentClient()?.sendVideo(data, bufferInfo(data, presentationTimeUs, isKeyFrame))
    }

    override fun sendAudio(data: ByteBuffer, presentationTimeUs: Long) {
        currentClient()?.sendAudio(data, bufferInfo(data, presentationTimeUs, false))
    }

    private fun currentClient(): RtmpClient? = synchronized(lock) { client }

    private fun isCurrent(attempt: Int): Boolean = synchronized(lock) { attempt == generation }

    private fun bufferInfo(data: ByteBuffer, presentationTimeUs: Long, isKeyFrame: Boolean) =
        MediaCodec.BufferInfo().apply {
            set(data.position(), data.remaining(), presentationTimeUs,
                if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }

    /** 이전 시도의 늦은 콜백은 무시합니다. */
    private fun update(attempt: Int, next: RtmpTargetState, reason: RtmpFailure?) {
        synchronized(lock) {
            if (attempt != generation) return
            state.set(next)
            failure.set(reason)
        }
        onStateChanged(this)
    }

    override fun toString(): String = "RtmpPublishTarget(provider=$provider, state=${state.get()})"
}
