package com.framework.innolive.feature.live.direct.publish

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.HeartbeatTarget
import com.framework.innolive.feature.live.direct.HeartbeatVideo

internal data class TargetPublishStats(
    val provider: BroadcastProvider,
    val connected: Boolean,
    val bitrateKbps: Int?,
    val reconnects: Int,
)

/** 하트비트로 보낼 송출 상태입니다. 누적 값은 송출 시작 이후 누적입니다. */
internal data class DirectPublishStats(
    val targets: List<TargetPublishStats>,
    val fps: Double,
    val droppedFrames: Long,
    val width: Int,
    val height: Int,
) {
    fun heartbeatTargets(): List<HeartbeatTarget> = targets.map {
        HeartbeatTarget(it.provider, it.connected, it.bitrateKbps, it.reconnects)
    }

    fun heartbeatVideo(): HeartbeatVideo = HeartbeatVideo(fps, droppedFrames, width, height)
}

/**
 * 인코더에 들어간 프레임 수로 fps를 잽니다. 마지막 조회 이후 들어온 프레임 수를
 * 경과 시간으로 나누므로 하트비트 주기 평균입니다.
 */
internal class FrameRateMeter(private val nowNs: () -> Long) {
    private val lock = Any()
    private var frames = 0L
    private var windowStartNs: Long? = null

    fun onFrame() = synchronized(lock) {
        if (windowStartNs == null) windowStartNs = nowNs()
        frames++
    }

    fun sample(): Double = synchronized(lock) {
        val start = windowStartNs ?: return 0.0
        val now = nowNs()
        val elapsedNs = now - start
        val fps = if (elapsedNs <= 0) 0.0 else frames * 1_000_000_000.0 / elapsedNs
        frames = 0
        windowStartNs = now
        fps
    }
}
