package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.SessionNotice
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import com.framework.innolive.feature.live.direct.HeartbeatAnonymization
import com.framework.innolive.feature.live.direct.HeartbeatStopReason
import com.framework.innolive.feature.live.direct.HeartbeatTarget
import com.framework.innolive.feature.live.direct.HeartbeatVideo
import com.framework.innolive.feature.live.direct.publish.RtmpFailure
import com.framework.innolive.feature.live.direct.publish.RtmpTargetState

/*
 * 직접 송출 상태 머신(C4)과 하트비트(C5)·재연결(C6)·생명주기(C7)·화면(C8) 사이의 경계입니다.
 * 각 작업은 이 타입만 보고 따로 구현하고, 컨트롤러가 조립합니다.
 */

/** 직접 송출을 켭니다. 상태 머신(C4)이 끝나기 전에는 꺼 두어 기존 서버 중계 동작을 유지합니다. */
internal object DirectPublishFeature {
    const val ENABLED = false
}

/** 화면이 고른 직접 송출 구성입니다. 세션을 만들 때 `broadcast_resolution`과 송출 대상이 정해집니다. */
internal data class DirectBroadcastPlan(
    val resolution: DirectPublishResolution = DirectPublishResolution.HD_720,
    val providers: Set<BroadcastProvider> = setOf(BroadcastProvider.YOUTUBE),
) {
    init {
        require(providers.isNotEmpty()) { "송출 대상이 없습니다." }
    }
}

/** 카메라 대신 대기 화면과 무음을 보내는 이유입니다. 하나라도 남아 있으면 대기 화면을 보냅니다. */
internal enum class SlateReason {
    /** 사용자가 일시 중지했습니다. 서버 pause API는 직접 송출에서 409이므로 앱이 처리합니다. */
    USER_PAUSE,

    /** 앱이 백그라운드로 가 카메라가 멈췄습니다. */
    BACKGROUND,

    /** 다른 앱이 카메라를 가져갔거나 카메라를 열 수 없습니다. */
    CAMERA_UNAVAILABLE,
}

/** 대기 화면 전환입니다. 상태 머신(C4)이 구현하고 생명주기(C7)가 씁니다. */
internal fun interface DirectMediaControl {
    fun setSlate(reason: SlateReason, active: Boolean)
}

// --- C5 하트비트 ---

/** 하트비트 한 번에 담을 송출 상태입니다. 기기 상태는 하트비트 쪽에서 채웁니다. */
internal data class HeartbeatInputs(
    val targets: List<HeartbeatTarget>,
    val video: HeartbeatVideo,
    val anonymization: HeartbeatAnonymization,
)

/** 라이브 대상이 없으면 null을 돌려줍니다. 그동안 하트비트를 보내지 않습니다. */
internal fun interface HeartbeatInputSource {
    fun current(): HeartbeatInputs?
}

/** 하트비트 결과입니다. 메인 스레드가 아닌 곳에서 올 수 있습니다. */
internal interface HeartbeatListener {
    /** 서버가 끝낸 대상입니다. 즉시 연결을 끊습니다. */
    fun onStopTargets(providers: List<BroadcastProvider>)

    /** 세션이 사라졌거나 라이브 대상이 없습니다. 모든 연결을 끊습니다. */
    fun onStopAll(reason: HeartbeatStopReason)

    fun onNotices(notices: List<SessionNotice>, broadcastRemainingSeconds: Long?)

    /** 필수 값 누락 등 앱 구현 오류입니다. 송출은 유지합니다. */
    fun onInvalid(field: String?)
}

internal interface HeartbeatScheduler : AutoCloseable {
    /** golive 성공 직후 부릅니다. 바로 한 번 보내고 이후 주기적으로 보냅니다. */
    fun start(session: CreatedSession)

    fun stop()

    override fun close() = stop()
}

// --- C6 재연결 ---

/** 재연결 판단에 따라 상태 머신이 수행할 동작입니다. */
internal interface ReconnectActions {
    fun reconnect(provider: BroadcastProvider)

    /** 재연결을 포기했습니다. 상태 머신이 해당 대상에 stop을 보내고 연결을 정리합니다. */
    fun giveUp(provider: BroadcastProvider, failure: RtmpFailure?)
}

internal interface ReconnectSupervisor : AutoCloseable {
    fun onTargetStateChanged(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure?)

    /** 사용자가 끊었거나 서버가 끝낸 대상입니다. 더 이상 재연결하지 않습니다. */
    fun onTargetRemoved(provider: BroadcastProvider)
}

// --- C7 생명주기 ---

/** 라이브 동안 프로세스와 네트워크를 유지합니다(foreground service). */
internal interface BroadcastKeepAlive {
    /** 앱이 화면에 있을 때 golive 직전에 부릅니다. */
    fun onBroadcastStarting()

    fun onBroadcastEnded()
}

// --- C8 화면 ---

internal enum class LocalTargetStatus { CONNECTING, PUBLISHING, RECONNECTING, FAILED, STOPPED }

internal enum class MicrophoneStatus { OK, UNAVAILABLE, SILENCED }

/** 서버 응답에 없는 직접 송출 상태입니다. 서버 대상의 `stream.status`는 직접 송출에서 항상 `idle`입니다. */
internal data class DirectBroadcastUiState(
    val targets: Map<BroadcastProvider, LocalTargetStatus> = emptyMap(),
    val microphone: MicrophoneStatus = MicrophoneStatus.OK,
    val slate: Set<SlateReason> = emptySet(),
    val thermalWarning: Boolean = false,
    /** `prepared`로 머물 수 있는 마감입니다(elapsedRealtime). 지나면 서버가 세션을 지웁니다. */
    val preparedDeadlineElapsedMillis: Long? = null,
)

/** 다른 작업이 들어오기 전까지 쓰는 빈 구현입니다. */
internal object NoopHeartbeatScheduler : HeartbeatScheduler {
    override fun start(session: CreatedSession) = Unit
    override fun stop() = Unit
}

internal object NoopReconnectSupervisor : ReconnectSupervisor {
    override fun onTargetStateChanged(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure?) = Unit
    override fun onTargetRemoved(provider: BroadcastProvider) = Unit
    override fun close() = Unit
}

internal object NoopBroadcastKeepAlive : BroadcastKeepAlive {
    override fun onBroadcastStarting() = Unit
    override fun onBroadcastEnded() = Unit
}
