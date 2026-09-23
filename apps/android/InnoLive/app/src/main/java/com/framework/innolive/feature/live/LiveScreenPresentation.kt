package com.framework.innolive.feature.live

import androidx.annotation.StringRes
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
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
    @param:StringRes val broadcastButtonTextRes: Int,
    val isBroadcastButtonEnabled: Boolean,
    val broadcastAction: LiveBroadcastAction,
    val broadcastStatusText: UiText?,
    val isBroadcastStatusDefault: Boolean,
    val isBroadcastStatusError: Boolean,
)

fun buildLiveScreenPresentation(
    connectionState: WebRtcConnectionState,
    broadcastState: BroadcastState,
    selectedPlatform: String?,
    broadcastStatus: UiText,
    isBroadcastStatusDefault: Boolean = false,
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
        connectionState == WebRtcConnectionState.FAILED -> null

        // Hide only a known state description that repeats the primary button.
        isBroadcastStatusDefault -> null

        broadcastState != BroadcastState.IDLE -> broadcastStatus
        else -> null
    }

    return LiveScreenPresentation(
        isConnected = isConnected,
        isConnecting = isConnecting,
        isBroadcastLive = isBroadcastLive,
        isBroadcastPrepared = isBroadcastPrepared,
        isBroadcastPaused = isBroadcastPaused,
        isBroadcastBusy = isBroadcastBusy,
        broadcastButtonTextRes = when {
            isBroadcastPaused -> R.string.broadcast_state_paused
            isBroadcastLive -> R.string.broadcast_state_live
            isBroadcastPrepared -> R.string.broadcast_state_prepared
            broadcastState == BroadcastState.PAUSING -> R.string.broadcast_state_pausing
            broadcastState == BroadcastState.RESUMING -> R.string.broadcast_state_resuming
            broadcastState == BroadcastState.CANCELLING_PREPARATION -> R.string.broadcast_state_cancelling
            broadcastState == BroadcastState.STOPPING -> R.string.broadcast_state_stopping
            isBroadcastBusy -> R.string.broadcast_state_preparing
            else -> R.string.action_prepare_broadcast
        },
        isBroadcastButtonEnabled = !isConnecting && !isBroadcastBusy &&
            (!(isBroadcastLive || isBroadcastPrepared) || isConnected),
        broadcastAction = broadcastAction,
        broadcastStatusText = broadcastStatusText,
        isBroadcastStatusDefault = isBroadcastStatusDefault,
        isBroadcastStatusError = broadcastState == BroadcastState.FAILED,
    )
}

/** Test-only compatibility for callers that construct a transient status literal. */
fun buildLiveScreenPresentation(
    connectionState: WebRtcConnectionState,
    broadcastState: BroadcastState,
    selectedPlatform: String?,
    broadcastStatus: String,
    isBroadcastStatusDefault: Boolean = false,
    isPreparingBroadcast: Boolean = false,
): LiveScreenPresentation = buildLiveScreenPresentation(
    connectionState = connectionState,
    broadcastState = broadcastState,
    selectedPlatform = selectedPlatform,
    broadcastStatus = UiText.Dynamic(broadcastStatus),
    isBroadcastStatusDefault = isBroadcastStatusDefault,
    isPreparingBroadcast = isPreparingBroadcast,
)

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
