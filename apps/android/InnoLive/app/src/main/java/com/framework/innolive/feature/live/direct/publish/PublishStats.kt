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
    /** 프로필 목표 fps와 영상 비트레이트입니다. 화질 제한 사유를 판단할 때만 씁니다. */
    val targetFps: Int = 0,
    val targetVideoBitrateKbps: Int = 0,
    /** 대기 화면을 보내는 중이면 fps·비트레이트가 낮아도 제한으로 보지 않습니다. */
    val slateActive: Boolean = false,
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

/**
 * 직접 송출 통계로 상태 패널의 화질 제한 사유를 정합니다. WebRTC `qualityLimitationReason`과 같은 값을 돌려줘
 * [com.framework.innolive.feature.live.status.BroadcastUplinkQualityTracker]가 서버 송출과 같은 기준으로
 * 경고를 띄웁니다.
 *
 * - 네트워크: 연결이 끊겼거나, 지난 판단 이후 재연결이 있었거나, 실제 비트레이트가 목표의 절반 미만
 * - 기기: 인코더 입력 fps가 목표의 80% 미만(가림 처리·카메라가 따라오지 못함)
 *
 * 재연결과 끊김을 먼저 봅니다. 비트레이트는 fps가 낮으면 함께 떨어지므로 기기 판단 뒤에 봅니다.
 */
internal class DirectUplinkLimitation {
    private var lastReconnects = emptyMap<BroadcastProvider, Int>()

    @Synchronized
    fun reason(stats: DirectPublishStats): String {
        val reconnected = stats.targets.any { it.reconnects > (lastReconnects[it.provider] ?: it.reconnects) }
        lastReconnects = stats.targets.associate { it.provider to it.reconnects }
        val lowBitrate = stats.targetVideoBitrateKbps > 0 && stats.targets.any { target ->
            target.connected && target.bitrateKbps != null &&
                target.bitrateKbps < stats.targetVideoBitrateKbps * LOW_BITRATE_RATIO
        }
        return when {
            stats.targets.any { !it.connected } || reconnected -> NETWORK
            stats.slateActive -> NONE
            stats.targetFps > 0 && stats.fps < stats.targetFps * LOW_FPS_RATIO -> DEVICE
            lowBitrate -> NETWORK
            else -> NONE
        }
    }

    private companion object {
        const val NETWORK = "bandwidth"
        const val DEVICE = "cpu"
        const val NONE = "none"
        const val LOW_FPS_RATIO = 0.8
        const val LOW_BITRATE_RATIO = 0.5
    }
}
