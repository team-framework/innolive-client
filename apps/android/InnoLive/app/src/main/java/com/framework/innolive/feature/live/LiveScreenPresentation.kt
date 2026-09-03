package com.framework.innolive.feature.live

enum class LiveBroadcastAction {
    STOP_BROADCAST,
    GO_LIVE,
    PREPARE_BROADCAST,
    SELECT_PLATFORM,
}

data class LiveScreenPresentation(
    val isConnected: Boolean,
    val isConnecting: Boolean,
    val isBroadcastLive: Boolean,
    val isBroadcastPrepared: Boolean,
    val isBroadcastBusy: Boolean,
    val broadcastButtonText: String,
    val isBroadcastButtonEnabled: Boolean,
    val broadcastAction: LiveBroadcastAction,
    val broadcastStatusText: String,
    val isBroadcastStatusError: Boolean,
)

fun buildLiveScreenPresentation(
    connectionState: WebRtcConnectionState,
    broadcastState: BroadcastState,
    selectedPlatform: String?,
    broadcastStatus: String,
): LiveScreenPresentation {
    val isConnected = connectionState == WebRtcConnectionState.CONNECTED
    val isConnecting = connectionState == WebRtcConnectionState.CONNECTING
    val isBroadcastLive = broadcastState == BroadcastState.LIVE
    val isBroadcastPrepared = broadcastState == BroadcastState.PREPARED
    val isBroadcastBusy = broadcastState.isBusy
    val broadcastAction = when {
        isBroadcastLive -> LiveBroadcastAction.STOP_BROADCAST
        isBroadcastPrepared -> LiveBroadcastAction.GO_LIVE
        selectedPlatform == "YouTube" -> LiveBroadcastAction.PREPARE_BROADCAST
        else -> LiveBroadcastAction.SELECT_PLATFORM
    }
    val broadcastStatusText = when {
        connectionState == WebRtcConnectionState.FAILED ->
            "비식별화 연결에 실패하였습니다."

        broadcastState != BroadcastState.IDLE -> broadcastStatus
        else -> ""
    }

    return LiveScreenPresentation(
        isConnected = isConnected,
        isConnecting = isConnecting,
        isBroadcastLive = isBroadcastLive,
        isBroadcastPrepared = isBroadcastPrepared,
        isBroadcastBusy = isBroadcastBusy,
        broadcastButtonText = when {
            isBroadcastLive -> "방송 종료"
            isBroadcastPrepared -> "라이브 시작"
            isBroadcastBusy -> "방송 준비 중"
            else -> "방송 준비"
        },
        isBroadcastButtonEnabled = isConnected && !isBroadcastBusy,
        broadcastAction = broadcastAction,
        broadcastStatusText = broadcastStatusText,
        isBroadcastStatusError =
            connectionState == WebRtcConnectionState.FAILED ||
                broadcastState == BroadcastState.FAILED,
    )
}

private val BroadcastState.isBusy: Boolean
    get() = when (this) {
        BroadcastState.SAVING_SETTINGS,
        BroadcastState.PREPARING,
        BroadcastState.GOING_LIVE,
        BroadcastState.STOPPING -> true
        else -> false
    }
