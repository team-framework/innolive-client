package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceInfo
import com.framework.innolive.feature.live.direct.broadcast.AndroidNetworkAvailability
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastController
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastDependencies
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastUiState
import com.framework.innolive.feature.live.direct.broadcast.DirectPublishFeature
import com.framework.innolive.feature.live.direct.broadcast.RtmpReconnectSupervisor
import com.framework.innolive.feature.live.status.UplinkVideoStats
import com.framework.innolive.ui.text.ServerErrorGuidance
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * 방송 화면이 쓰는 송출 경로입니다. 서버 중계([WebRtcConnection])와 앱 직접 송출
 * ([DirectBroadcastController])이 같은 동작을 구현합니다.
 *
 * 준비 대기([WebRtcSessionViewModel.prepareBroadcast])는 [LiveBroadcastCallbacks.onInitialSignalingStarted]와
 * CONNECTED 상태를 기다리므로, 직접 송출도 세션 생성을 시작할 때 이 콜백을 부르고 첫 가림 처리 프레임이
 * 나온 뒤 CONNECTED를 알립니다.
 */
internal interface LiveBroadcastTransport : AutoCloseable {
    fun start()
    fun close(onComplete: (() -> Unit)?)
    fun setPreferredAudioInput(audioInput: AudioDeviceInfo?)
    fun setAnonymizationEnabled(
        enabled: Boolean,
        onComplete: (AnonymizationState?, AnonymizationFailure?) -> Unit,
    )
    fun saveBroadcastSettings(settings: BroadcastSettings)
    fun prepareBroadcast(settings: BroadcastSettings, allowConcurrent: Boolean = false): Boolean
    fun prepareBroadcast(chzzkSettings: ChzzkBroadcastSettings): Boolean
    fun goLive(onAccepted: () -> Unit = {}): Boolean
    fun pauseBroadcast()
    fun resumeBroadcast()
    fun stopBroadcast()

    override fun close() = close(null)
}

/** 송출 경로가 화면 상태로 돌려주는 콜백입니다. 의미는 [WebRtcConnection] 생성자 인자와 같습니다. */
internal class LiveBroadcastCallbacks(
    val onStateChanged: (WebRtcConnectionState, ConnectionFailure?) -> Unit,
    val onRemoteTrackChanged: (VideoTrack?) -> Unit,
    val onLocalMediaReady: (CameraFrameAnalyzer, EglBase.Context) -> Unit,
    val onLocalMediaCleared: () -> Unit,
    val onBroadcastStateChanged: (BroadcastState, BroadcastEvent?) -> Unit,
    val onAnonymizationStateConfirmed: (AnonymizationState) -> Unit,
    val onLocalVideoTrackChanged: (VideoTrack?) -> Unit,
    val onInitialSignalingStarted: () -> Unit,
    val onSessionSnapshotChanged: (SessionSnapshot) -> Unit,
    val onServerError: (ServerErrorGuidance) -> Unit,
    val onUplinkVideoStats: (UplinkVideoStats) -> Unit,
    /** 직접 송출에서만 옵니다. 로컬 RTMP 연결·마이크·대기 화면 상태입니다. */
    val onDirectUiStateChanged: (DirectBroadcastUiState) -> Unit = {},
)

/**
 * 송출 경로를 만들 때 필요한 값입니다. 송출 경로가 ViewModel 수명 동안 요청을 보관하므로
 * [context]는 화면이 넘긴 Activity가 아니라 application context로 정규화합니다.
 */
internal class LiveBroadcastTransportRequest(
    context: Context,
    val serverUrl: String,
    val accessToken: String,
    val refreshAccessToken: suspend () -> String,
    val initialAnonymizationEnabled: Boolean,
    val initialOnDeviceProcessing: Boolean,
    val provider: BroadcastProvider,
    val preferredAudioInput: AudioDeviceInfo?,
    /** 직접 송출의 해상도와 대상입니다. 서버 중계에서는 쓰지 않습니다. */
    val directPlan: DirectBroadcastPlan,
    val callbacks: LiveBroadcastCallbacks,
) {
    val context: Context = context.applicationContext
}

/**
 * 기본 송출 경로입니다. 온디바이스를 고르고 직접 송출이 켜져 있으면 앱 직접 송출을, 그 밖에는
 * 기존 서버 중계를 씁니다.
 */
internal fun createLiveBroadcastTransport(request: LiveBroadcastTransportRequest): LiveBroadcastTransport =
    if (request.initialOnDeviceProcessing && DirectPublishFeature.ENABLED) {
        DirectBroadcastController(request, DirectBroadcastDependencies(
            reconnect = { actions -> RtmpReconnectSupervisor(actions, AndroidNetworkAvailability(request.context)) },
        ))
    }
    else with(request.callbacks) {
        WebRtcConnection(
            context = request.context,
            serverUrl = request.serverUrl,
            accessToken = request.accessToken,
            refreshAccessToken = request.refreshAccessToken,
            initialAnonymizationEnabled = request.initialAnonymizationEnabled,
            initialOnDeviceProcessing = request.initialOnDeviceProcessing,
            provider = request.provider,
            preferredAudioInput = request.preferredAudioInput,
            onStateChanged = onStateChanged,
            onRemoteTrackChanged = onRemoteTrackChanged,
            onLocalMediaReady = onLocalMediaReady,
            onLocalMediaCleared = onLocalMediaCleared,
            onBroadcastStateChanged = onBroadcastStateChanged,
            onAnonymizationStateConfirmed = onAnonymizationStateConfirmed,
            onLocalVideoTrackChanged = onLocalVideoTrackChanged,
            onInitialSignalingStarted = onInitialSignalingStarted,
            onSessionSnapshotChanged = onSessionSnapshotChanged,
            onServerError = onServerError,
            onUplinkVideoStats = onUplinkVideoStats,
        )
    }
