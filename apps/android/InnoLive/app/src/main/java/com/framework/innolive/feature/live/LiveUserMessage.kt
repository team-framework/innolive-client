package com.framework.innolive.feature.live

import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText

enum class ConnectionFailure {
    TIMEOUT,
    DISCONNECTED,
    CONFIGURATION,
    EXISTING_BROADCAST,
    MICROPHONE_UNAVAILABLE,
    MICROPHONE_BLOCKED,
    GENERIC,
}

enum class BroadcastFailure {
    PREVIEW_REQUIRED,
    AUDIENCE_REQUIRED,
    YOUTUBE_NOT_CONNECTED,
    YOUTUBE_LIVE_BLOCKED,
    YOUTUBE_RECONNECT,
    YOUTUBE_PREPARE,
    YOUTUBE_STOPPED,
    YOUTUBE_NOT_READY,
    YOUTUBE_PAUSE,
    YOUTUBE_RESUME,
    REQUEST,
}

sealed interface BroadcastEvent {
    data class Failure(val failure: BroadcastFailure) : BroadcastEvent

    data object SettingsSaved : BroadcastEvent
}

internal enum class AnonymizationFailure {
    NOT_APPLIED,
    CONFIRMATION,
    REQUEST,
}

// 화면에 허용한 안내만 전달하여 서버·라이브러리의 내부 오류가 노출되지 않게 합니다.
internal fun connectionUserMessage(
    state: WebRtcConnectionState,
    failure: ConnectionFailure? = null,
): UiText? = when (state) {
    WebRtcConnectionState.IDLE -> null
    WebRtcConnectionState.CONNECTED -> UiText.Resource(R.string.preview_connected)
    WebRtcConnectionState.CONNECTING -> UiText.Resource(R.string.preview_connecting)
    WebRtcConnectionState.FAILED -> when (failure) {
        ConnectionFailure.TIMEOUT -> UiText.Resource(R.string.error_preview_timeout)
        ConnectionFailure.DISCONNECTED -> UiText.Resource(R.string.error_preview_disconnected)
        ConnectionFailure.CONFIGURATION -> UiText.Resource(R.string.error_connection_configuration)
        ConnectionFailure.EXISTING_BROADCAST -> UiText.Resource(R.string.error_existing_broadcast)
        ConnectionFailure.MICROPHONE_UNAVAILABLE -> UiText.Resource(R.string.error_microphone_unavailable)
        ConnectionFailure.MICROPHONE_BLOCKED -> UiText.Resource(R.string.error_microphone_blocked)
        ConnectionFailure.GENERIC, null -> UiText.Resource(R.string.error_preview_connect)
    }
}

internal data class BroadcastUserMessage(
    val text: UiText,
    val isStateDescription: Boolean,
)

internal fun broadcastUserMessage(
    state: BroadcastState,
    event: BroadcastEvent? = null,
): BroadcastUserMessage = when (event) {
    is BroadcastEvent.Failure -> broadcastError(event.failure)
    BroadcastEvent.SettingsSaved -> BroadcastUserMessage(
        text = UiText.Resource(R.string.broadcast_settings_saved),
        isStateDescription = false,
    )

    null -> broadcastStateMessage(state)
}

internal fun broadcastStateMessage(state: BroadcastState): BroadcastUserMessage = BroadcastUserMessage(
    text = UiText.Resource(
        when (state) {
            BroadcastState.IDLE -> R.string.broadcast_state_idle
            BroadcastState.SAVING_SETTINGS -> R.string.broadcast_settings_saving
            BroadcastState.PREPARING -> R.string.broadcast_state_preparing
            BroadcastState.PREPARED -> R.string.broadcast_state_prepared
            BroadcastState.GOING_LIVE -> R.string.broadcast_state_going_live
            BroadcastState.LIVE -> R.string.broadcast_state_live
            BroadcastState.PAUSING -> R.string.broadcast_state_pausing
            BroadcastState.PAUSED -> R.string.broadcast_state_paused
            BroadcastState.RESUMING -> R.string.broadcast_state_resuming
            BroadcastState.CANCELLING_PREPARATION -> R.string.broadcast_state_cancelling
            BroadcastState.STOPPING -> R.string.broadcast_state_stopping
            BroadcastState.FAILED -> R.string.error_request_failed
        },
    ),
    isStateDescription = state in setOf(
        BroadcastState.PREPARING,
        BroadcastState.LIVE,
        BroadcastState.PAUSING,
        BroadcastState.RESUMING,
        BroadcastState.CANCELLING_PREPARATION,
        BroadcastState.STOPPING,
    ),
)

internal fun anonymizationUserMessage(failure: AnonymizationFailure): UiText = when (failure) {
    AnonymizationFailure.NOT_APPLIED -> UiText.Resource(R.string.error_anonymization_not_applied)
    AnonymizationFailure.CONFIRMATION -> UiText.Resource(R.string.error_anonymization_confirmation)
    AnonymizationFailure.REQUEST -> UiText.Resource(R.string.error_anonymization_request)
}

private fun broadcastError(failure: BroadcastFailure) = BroadcastUserMessage(
    text = UiText.Resource(when (failure) {
        BroadcastFailure.PREVIEW_REQUIRED -> R.string.error_preview_required
        BroadcastFailure.AUDIENCE_REQUIRED -> R.string.validation_audience
        BroadcastFailure.YOUTUBE_NOT_CONNECTED -> R.string.error_youtube_not_connected
        BroadcastFailure.YOUTUBE_LIVE_BLOCKED -> R.string.error_youtube_live_blocked
        BroadcastFailure.YOUTUBE_RECONNECT -> R.string.error_youtube_reconnect
        BroadcastFailure.YOUTUBE_PREPARE -> R.string.error_youtube_prepare
        BroadcastFailure.YOUTUBE_STOPPED -> R.string.error_youtube_stopped
        BroadcastFailure.YOUTUBE_NOT_READY -> R.string.error_youtube_not_ready
        BroadcastFailure.YOUTUBE_PAUSE -> R.string.error_youtube_pause
        BroadcastFailure.YOUTUBE_RESUME -> R.string.error_youtube_resume
        BroadcastFailure.REQUEST -> R.string.error_request_failed
    }),
    isStateDescription = false,
)
