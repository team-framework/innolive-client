package com.framework.innolive.feature.live

import androidx.annotation.StringRes
import com.framework.innolive.R

@StringRes
fun broadcastSettingsSaveDisabledReason(
    provider: BroadcastProvider,
    connectionState: WebRtcConnectionState,
    broadcastState: BroadcastState,
    audienceSelected: Boolean,
): Int? = when {
    provider != BroadcastProvider.YOUTUBE -> R.string.broadcast_settings_youtube_only
    connectionState != WebRtcConnectionState.CONNECTED -> R.string.broadcast_settings_connection_required
    !audienceSelected -> R.string.validation_audience
    broadcastState == BroadcastState.SAVING_SETTINGS -> R.string.broadcast_settings_saving
    broadcastState in setOf(
        BroadcastState.PREPARING,
        BroadcastState.PREPARED,
        BroadcastState.GOING_LIVE,
        BroadcastState.LIVE,
        BroadcastState.STOPPING,
    ) -> R.string.broadcast_settings_save_unavailable
    else -> null
}
