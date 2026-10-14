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
    // 일시 중지·재개·준비 취소 중에도 서버 단계는 live 또는 prepared이므로 설정을 잠근다.
    broadcastState in setOf(
        BroadcastState.PREPARING,
        BroadcastState.PREPARED,
        BroadcastState.GOING_LIVE,
        BroadcastState.LIVE,
        BroadcastState.PAUSING,
        BroadcastState.PAUSED,
        BroadcastState.RESUMING,
        BroadcastState.CANCELLING_PREPARATION,
        BroadcastState.STOPPING,
    ) -> R.string.broadcast_settings_save_unavailable
    else -> null
}
