package com.framework.innolive.feature.live

enum class LiveBroadcastAction {
    SHOW_BROADCAST_ACTIONS,
    PREPARE_BROADCAST,
    SELECT_PLATFORM,
}

data class LiveScreenPresentation(
    val isConnected: Boolean,
    val isConnecting: Boolean,
    val isBroadcastLive: Boolean,
    val isBroadcastPrepared: Boolean,
    val isBroadcastPaused: Boolean,
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
    isPreparingBroadcast: Boolean = false,
): LiveScreenPresentation {
    val isConnected = connectionState == WebRtcConnectionState.CONNECTED
    val isConnecting = connectionState == WebRtcConnectionState.CONNECTING
    val isBroadcastLive = broadcastState == BroadcastState.LIVE || broadcastState == BroadcastState.PAUSED
    val isBroadcastPrepared = broadcastState == BroadcastState.PREPARED
    val isBroadcastPaused = broadcastState == BroadcastState.PAUSED
    val isBroadcastBusy = broadcastState.isBusy || isPreparingBroadcast
    val broadcastAction = when {
        isBroadcastLive || isBroadcastPrepared -> LiveBroadcastAction.SHOW_BROADCAST_ACTIONS
        selectedPlatform == "YouTube" -> LiveBroadcastAction.PREPARE_BROADCAST
        else -> LiveBroadcastAction.SELECT_PLATFORM
    }
    val broadcastStatusText = when {
        // The connection status above the broadcast controls already explains this failure.
        connectionState == WebRtcConnectionState.FAILED -> ""

        broadcastState != BroadcastState.IDLE -> broadcastStatus
        else -> ""
    }

    return LiveScreenPresentation(
        isConnected = isConnected,
        isConnecting = isConnecting,
        isBroadcastLive = isBroadcastLive,
        isBroadcastPrepared = isBroadcastPrepared,
        isBroadcastPaused = isBroadcastPaused,
        isBroadcastBusy = isBroadcastBusy,
        broadcastButtonText = when {
            isBroadcastPaused -> "방송 일시 중지"
            isBroadcastLive -> "방송 중"
            isBroadcastPrepared -> "방송 준비 완료"
            broadcastState == BroadcastState.PAUSING -> "방송 일시 중지 중"
            broadcastState == BroadcastState.RESUMING -> "방송 재개 중"
            broadcastState == BroadcastState.CANCELLING_PREPARATION -> "방송 준비 취소 중"
            broadcastState == BroadcastState.STOPPING -> "방송 종료 중"
            isBroadcastBusy -> "방송 준비 중"
            else -> "방송 준비"
        },
        isBroadcastButtonEnabled = !isConnecting && !isBroadcastBusy &&
            (!(isBroadcastLive || isBroadcastPrepared) || isConnected),
        broadcastAction = broadcastAction,
        broadcastStatusText = broadcastStatusText,
        isBroadcastStatusError = broadcastState == BroadcastState.FAILED,
    )
}

private val BroadcastState.isBusy: Boolean
    get() = when (this) {
        BroadcastState.SAVING_SETTINGS,
        BroadcastState.PREPARING,
        BroadcastState.GOING_LIVE,
        BroadcastState.PAUSING,
        BroadcastState.RESUMING,
        BroadcastState.CANCELLING_PREPARATION,
        BroadcastState.STOPPING -> true
        else -> false
    }
