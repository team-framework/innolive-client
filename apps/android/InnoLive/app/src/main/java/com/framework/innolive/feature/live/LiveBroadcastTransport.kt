package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceInfo
import com.framework.innolive.R
import com.framework.innolive.feature.live.direct.broadcast.AndroidNetworkAvailability
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastController
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastDependencies
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastUiState
import com.framework.innolive.feature.live.direct.broadcast.DirectPublishFeature
import com.framework.innolive.feature.live.direct.broadcast.RtmpReconnectSupervisor
import com.framework.innolive.feature.live.direct.broadcast.createDirectHeartbeatLoop
import com.framework.innolive.feature.live.direct.broadcast.createOnDevicePreviewTransport
import com.framework.innolive.feature.live.direct.lifecycle.ServiceBroadcastKeepAlive
import com.framework.innolive.feature.live.recording.RecordingAudioSink
import com.framework.innolive.feature.live.status.UplinkVideoStats
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.UiText
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
    /**
     * 송출을 유지한 채 다음 프레임부터 서버 AI의 블러 강도·영역을 바꿉니다. 성공하면 서버가 확정한 값을,
     * 실패하면 화면에 보여 줄 안내를 넘깁니다. 서버 AI 세션만 지원하므로 기본 구현은 거절합니다.
     */
    fun setMosaicConfig(config: MosaicConfig, onComplete: (MosaicConfig?, UiText?) -> Unit) {
        onComplete(null, UiText.Resource(R.string.mosaic_server_only))
    }
    fun saveBroadcastSettings(settings: BroadcastSettings)
    fun prepareBroadcast(settings: BroadcastSettings, allowConcurrent: Boolean = false): Boolean
    fun prepareBroadcast(chzzkSettings: ChzzkBroadcastSettings): Boolean

    /**
     * 설정을 넘긴 플랫폼마다 설정을 저장하고 준비합니다. 둘 다 넘기면 동시 송출이며 이미 준비한 플랫폼은
     * 건너뜁니다. 한 플랫폼의 실패가 다른 플랫폼의 준비를 막지 않습니다.
     */
    fun prepareTargets(
        youtube: BroadcastSettings?,
        chzzk: ChzzkBroadcastSettings?,
        allowConcurrent: Boolean = false,
    ): Boolean = when {
        youtube != null && chzzk == null -> prepareBroadcast(youtube, allowConcurrent)
        youtube == null && chzzk != null -> prepareBroadcast(chzzk)
        else -> false
    }
    fun goLive(onAccepted: () -> Unit = {}): Boolean

    /**
     * 송출하는 마이크 음성을 녹화기에도 넘깁니다. null이면 끊습니다. 송출 경로가 음성을 만들지 않는 동안에는
     * 아무것도 오지 않습니다. 기본 구현은 녹화 음성을 제공하지 않습니다.
     */
    fun setRecordingAudioSink(sink: RecordingAudioSink?) = Unit
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
    /** 직접 송출에서만 옵니다. 방송 종료·실패로 세션을 지웠거나 서버에서 사라져 세션 상태가 없어졌습니다. */
    val onSessionSnapshotCleared: () -> Unit = {},
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
    /** 송출 해상도와 대상입니다. 서버 중계는 세션을 만들 때 해상도만 씁니다. */
    val directPlan: DirectBroadcastPlan,
    val callbacks: LiveBroadcastCallbacks,
    /** 플랫폼 없이 온디바이스 가림 처리 미리보기만 엽니다. 서버에 연결하지 않습니다. */
    val previewOnly: Boolean = false,
    /** 실험실에서 얼굴 합성을 켰습니다. 서버 중계 세션을 서버 AI로 만들 때만 씁니다. */
    val faceSwap: Boolean = false,
) {
    val context: Context = context.applicationContext
}

/**
 * 기본 송출 경로입니다. 미리보기만 요청하면 온디바이스 미리보기를, 온디바이스를 고르고 직접 송출이 켜져 있으면
 * 앱 직접 송출을, 그 밖에는 기존 서버 중계를 씁니다.
 */
internal fun createLiveBroadcastTransport(request: LiveBroadcastTransportRequest): LiveBroadcastTransport =
    if (request.previewOnly) createOnDevicePreviewTransport(request)
    else if (request.initialOnDeviceProcessing && DirectPublishFeature.ENABLED) createDirectBroadcastController(request)
    else with(request.callbacks) {
        WebRtcConnection(
            context = request.context,
            serverUrl = request.serverUrl,
            accessToken = request.accessToken,
            refreshAccessToken = request.refreshAccessToken,
            initialAnonymizationEnabled = request.initialAnonymizationEnabled,
            initialOnDeviceProcessing = request.initialOnDeviceProcessing,
            provider = request.provider,
            broadcastResolution = request.directPlan.resolution,
            faceAnonymization = sessionFaceAnonymization(request.faceSwap, request.initialOnDeviceProcessing),
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

/**
 * 직접 송출 컨트롤러를 만듭니다. 생명주기 유지(C7)는 컨트롤러가 만들어진 뒤에 대기 화면 전환과
 * 알림의 방송 종료를 컨트롤러로 넘깁니다.
 */
private fun createDirectBroadcastController(request: LiveBroadcastTransportRequest): DirectBroadcastController {
    var controller: DirectBroadcastController? = null
    return DirectBroadcastController(
        request,
        DirectBroadcastDependencies(
            heartbeat = { api, input, listener -> createDirectHeartbeatLoop(request.context, api, input, listener) },
            reconnect = { actions -> RtmpReconnectSupervisor(actions, AndroidNetworkAvailability(request.context)) },
            keepAlive = { context ->
                ServiceBroadcastKeepAlive(
                    context = context,
                    mediaControl = { reason, active -> controller?.setSlate(reason, active) },
                    onStopRequested = { controller?.stopBroadcast() },
                )
            },
        ),
    ).also { controller = it }
}
