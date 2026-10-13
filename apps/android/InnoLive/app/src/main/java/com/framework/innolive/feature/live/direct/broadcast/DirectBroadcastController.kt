package com.framework.innolive.feature.live.direct.broadcast

import android.content.Context
import android.media.AudioDeviceInfo
import com.framework.innolive.feature.live.AnonymizationFailure
import com.framework.innolive.feature.live.AnonymizationState
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.ConnectionFailure
import com.framework.innolive.feature.live.LiveBroadcastTransport
import com.framework.innolive.feature.live.LiveBroadcastTransportRequest
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.direct.DirectPublishApi

/** 하트비트·재연결·생명주기 구현을 바꿔 끼우는 지점입니다. 각 작업이 기본값을 실제 구현으로 바꿉니다. */
internal class DirectBroadcastDependencies(
    val heartbeat: (DirectPublishApi, HeartbeatInputSource, HeartbeatListener) -> HeartbeatScheduler =
        { _, _, _ -> NoopHeartbeatScheduler },
    val reconnect: (ReconnectActions) -> ReconnectSupervisor = { NoopReconnectSupervisor },
    val keepAlive: (Context) -> BroadcastKeepAlive = { NoopBroadcastKeepAlive },
)

/**
 * 앱 직접 송출 상태 머신입니다(contracts/api/direct-publish-v1.md). 세션 생성·준비·라이브 전환·종료를
 * 서버 API([DirectPublishApi])와 송출 엔진(`DirectPublisher`)으로 진행합니다.
 *
 * 상태 머신(C4) 구현 전의 골격입니다. [DirectPublishFeature.ENABLED]가 꺼져 있어 화면에서 만들어지지
 * 않으며, 만들어지더라도 시작을 실패로 알려 원본이나 빈 송출이 나가지 않게 합니다.
 */
internal class DirectBroadcastController(
    private val request: LiveBroadcastTransportRequest,
    @Suppress("unused") private val dependencies: DirectBroadcastDependencies = DirectBroadcastDependencies(),
) : LiveBroadcastTransport, DirectMediaControl {
    override fun start() {
        request.callbacks.onStateChanged(WebRtcConnectionState.FAILED, ConnectionFailure.GENERIC)
    }

    override fun close(onComplete: (() -> Unit)?) {
        onComplete?.invoke()
    }

    override fun setPreferredAudioInput(audioInput: AudioDeviceInfo?) = Unit

    override fun setAnonymizationEnabled(
        enabled: Boolean,
        onComplete: (AnonymizationState?, AnonymizationFailure?) -> Unit,
    ) = onComplete(null, AnonymizationFailure.REQUEST)

    override fun saveBroadcastSettings(settings: BroadcastSettings) = Unit

    override fun prepareBroadcast(settings: BroadcastSettings, allowConcurrent: Boolean): Boolean = false

    override fun prepareBroadcast(chzzkSettings: ChzzkBroadcastSettings): Boolean = false

    override fun goLive(onAccepted: () -> Unit): Boolean = false

    override fun pauseBroadcast() = Unit

    override fun resumeBroadcast() = Unit

    override fun stopBroadcast() = Unit

    override fun setSlate(reason: SlateReason, active: Boolean) = Unit
}
