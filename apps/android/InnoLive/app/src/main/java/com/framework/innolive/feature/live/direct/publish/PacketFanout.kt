package com.framework.innolive.feature.live.direct.publish

import com.framework.innolive.feature.live.BroadcastProvider
import java.nio.ByteBuffer

/** 인코더 출력 하나입니다. [data]의 position·limit이 전송 범위입니다. */
internal data class EncodedPacket(
    val data: ByteBuffer,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
)

/** RTMP 연결 하나에 보낼 수 있는 동작입니다. 실제 구현은 [RtmpPublishTarget]입니다. */
internal interface PublishEndpoint {
    val isPublishing: Boolean
    fun setVideoConfig(sps: ByteBuffer, pps: ByteBuffer)
    fun sendVideo(data: ByteBuffer, presentationTimeUs: Long, isKeyFrame: Boolean)
    fun sendAudio(data: ByteBuffer, presentationTimeUs: Long)
}

/**
 * 한 번 인코딩한 영상·음성을 여러 플랫폼 연결로 나눠 보냅니다.
 *
 * - 대상마다 송출이 허가된 뒤 첫 키프레임부터 보냅니다. 그 전 프레임과 음성은 버립니다.
 * - 대상마다 첫 키프레임 시각을 0으로 맞춰, 늦게 연결한 대상도 타임스탬프가 0부터 시작합니다.
 * - 키프레임을 기다리는 대상이 생기면 [requestKeyFrame]으로 인코더에 즉시 키프레임을 요청합니다.
 *
 * 인코더 출력 스레드 하나에서 호출합니다. 대상 추가·삭제만 다른 스레드에서 올 수 있습니다.
 */
internal class PacketFanout(private val requestKeyFrame: () -> Unit) {
    private class Route(val endpoint: PublishEndpoint) {
        var baseUs: Long? = null
        var configured = false
        var keyFrameRequested = false
    }

    private val lock = Any()
    private val routes = linkedMapOf<BroadcastProvider, Route>()
    private var sps: ByteBuffer? = null
    private var pps: ByteBuffer? = null

    fun add(provider: BroadcastProvider, endpoint: PublishEndpoint) = synchronized(lock) {
        routes[provider] = Route(endpoint)
    }

    fun remove(provider: BroadcastProvider): PublishEndpoint? = synchronized(lock) {
        routes.remove(provider)?.endpoint
    }

    fun providers(): List<BroadcastProvider> = synchronized(lock) { routes.keys.toList() }

    fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer) = synchronized(lock) {
        this.sps = sps.copyRemaining()
        this.pps = pps.copyRemaining()
        // 인코더가 다시 설정되면 모든 대상이 새 설정과 키프레임부터 다시 받아야 합니다.
        routes.values.forEach { it.configured = false; it.baseUs = null; it.keyFrameRequested = false }
    }

    fun onVideo(packet: EncodedPacket) {
        var needsKeyFrame = false
        synchronized(lock) {
            for (route in routes.values) {
                if (!route.endpoint.isPublishing) {
                    // 재연결은 새 RTMP 세션이므로 코덱 설정부터 다시 보냅니다.
                    route.baseUs = null
                    route.configured = false
                    route.keyFrameRequested = false
                    continue
                }
                if (route.baseUs == null) {
                    if (!packet.isKeyFrame) {
                        if (!route.keyFrameRequested) {
                            route.keyFrameRequested = true
                            needsKeyFrame = true
                        }
                        continue
                    }
                    if (!route.configured) {
                        val sps = sps ?: continue
                        val pps = pps ?: continue
                        route.endpoint.setVideoConfig(sps.duplicate(), pps.duplicate())
                        route.configured = true
                    }
                    route.baseUs = packet.presentationTimeUs
                }
                val base = route.baseUs ?: continue
                route.endpoint.sendVideo(packet.data.duplicate(), packet.presentationTimeUs - base, packet.isKeyFrame)
            }
        }
        if (needsKeyFrame) requestKeyFrame()
    }

    fun onAudio(packet: EncodedPacket) = synchronized(lock) {
        for (route in routes.values) {
            val base = route.baseUs ?: continue
            if (!route.endpoint.isPublishing || packet.presentationTimeUs < base) continue
            route.endpoint.sendAudio(packet.data.duplicate(), packet.presentationTimeUs - base)
        }
    }
}

private fun ByteBuffer.copyRemaining(): ByteBuffer {
    val source = duplicate()
    return ByteBuffer.allocate(source.remaining()).put(source).apply { flip() }
}

/**
 * 키프레임 간격을 앱이 직접 지킵니다. 플랫폼은 4초 이하 간격을 요구하는데, 인코더의
 * `KEY_I_FRAME_INTERVAL`은 기기마다 지켜진다는 보장이 없습니다. 입력이 멈춰 같은 프레임이
 * 반복되는 동안에도 간격을 유지하려고 시각 기준으로 요청합니다.
 *
 * 요청이 반영되기까지 몇 프레임 걸리므로 간격보다 [leadUs]만큼 먼저 요청하고, 키프레임이 오기 전에는
 * [retryUs]마다 한 번만 다시 요청합니다.
 */
internal class KeyFrameScheduler(
    private val intervalUs: Long,
    private val leadUs: Long = 100_000L,
    private val retryUs: Long = 500_000L,
) {
    private var lastKeyFrameUs: Long? = null
    private var lastRequestUs: Long? = null

    /** 인코더 출력마다 부릅니다. 키프레임을 요청해야 하면 true입니다. */
    fun onVideoPacket(presentationTimeUs: Long, isKeyFrame: Boolean): Boolean {
        if (isKeyFrame) {
            lastKeyFrameUs = presentationTimeUs
            lastRequestUs = null
            return false
        }
        val lastKey = lastKeyFrameUs ?: return false
        if (presentationTimeUs - lastKey < intervalUs - leadUs) return false
        val lastRequest = lastRequestUs
        if (lastRequest != null && presentationTimeUs - lastRequest < retryUs) return false
        lastRequestUs = presentationTimeUs
        return true
    }
}
