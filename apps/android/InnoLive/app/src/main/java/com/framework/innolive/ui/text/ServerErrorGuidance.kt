package com.framework.innolive.ui.text

import com.framework.innolive.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ServerErrorAction {
    NONE, CONNECT, RECONNECT, HELP, CONFIRM_CONCURRENT, RETRY, LOGIN, EDIT_SETTINGS,
}

data class ServerErrorGuidance(
    val code: String,
    val message: UiText,
    val action: ServerErrorAction = ServerErrorAction.NONE,
    val helpUrl: String? = null,
    val field: String? = null,
)

// 알려진 오류만 제품 문구로 바꿉니다. 알 수 없는 오류의 기존 fallback은 호출자가 유지합니다.
fun serverErrorGuidance(
    code: String?,
    statusCode: Int? = null,
    helpUrl: String? = null,
    field: String? = null,
    reason: String? = null,
): ServerErrorGuidance? {
    val knownCode = if (statusCode == 401) "unauthorized" else code ?: return null
    val message = when (knownCode) {
        "streaming_not_connected" -> R.string.error_youtube_not_connected
        "streaming_reconnect_required" -> R.string.error_youtube_reconnect
        "live_streaming_blocked" -> R.string.error_live_activation
        "channel_already_live" -> R.string.error_channel_already_live
        "plan_resolution_not_allowed" -> R.string.error_plan_resolution
        "plan_simulcast_not_allowed" -> R.string.error_plan_simulcast
        "plan_server_streaming_not_allowed" -> R.string.error_plan_server_streaming
        "monthly_limit_exhausted" -> R.string.error_monthly_limit
        "broadcast_limit_reached" -> R.string.error_broadcast_limit
        "egress_slots_exhausted", "capacity_exceeded", "server_busy" -> R.string.error_stream_capacity
        "streaming_quota_exceeded" -> R.string.error_streaming_quota
        "streaming_rate_limited" -> R.string.error_streaming_rate_limit
        "streaming_account_in_use" -> R.string.error_streaming_account_in_use
        "streaming_prepare_failed", "streaming_golive_failed", "streaming_update_failed" ->
            R.string.error_streaming_operation
        "withdrawal_in_progress" -> R.string.error_withdrawal_in_progress
        "session_already_exists" -> R.string.error_previous_session_cleanup
        "unauthorized" -> R.string.error_authentication_expired
        "bad_request" -> when (reason) {
            "required", "missing", "empty" -> R.string.error_field_required
            else -> R.string.error_field_invalid
        }
        "field_not_changeable_live" -> R.string.error_field_not_changeable_live
        else -> return null
    }
    val safeHelpUrl = helpUrl?.toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
    val action = when (knownCode) {
        "streaming_not_connected" -> ServerErrorAction.CONNECT
        "streaming_reconnect_required" -> ServerErrorAction.RECONNECT
        "live_streaming_blocked" -> if (safeHelpUrl != null) ServerErrorAction.HELP else ServerErrorAction.NONE
        "channel_already_live" -> ServerErrorAction.CONFIRM_CONCURRENT
        "streaming_prepare_failed", "streaming_golive_failed", "streaming_update_failed" -> ServerErrorAction.RETRY
        "unauthorized" -> ServerErrorAction.LOGIN
        "bad_request", "field_not_changeable_live" -> ServerErrorAction.EDIT_SETTINGS
        else -> ServerErrorAction.NONE
    }
    return ServerErrorGuidance(knownCode, UiText.Resource(message), action, safeHelpUrl, field)
}
