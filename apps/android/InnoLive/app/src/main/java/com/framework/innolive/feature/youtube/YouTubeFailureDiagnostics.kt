package com.framework.innolive.feature.youtube

import com.google.android.gms.common.api.ApiException

/** Never log an exception message, OAuth code, response body, or account identifier. */
internal fun youtubeFailureDiagnostic(exception: Throwable?): String = when (exception) {
    is ApiException -> "source=google status=${exception.statusCode}"
    is YouTubeApiException -> "source=server status=${exception.statusCode} " +
        "code=${safeServerErrorCode(exception.errorCode)} cause=${exception.cause?.javaClass?.simpleName}"
    else -> "source=client type=${exception?.javaClass?.simpleName} " +
        "cause=${exception?.cause?.javaClass?.simpleName}"
}

internal fun safeServerErrorCode(code: String?): String = when (code) {
    null, "" -> "absent"
    "unauthorized", "forbidden", "internal_error", "invalid_request", "invalid_argument",
    "not_found", "conflict", "too_many_requests", "service_unavailable", "upstream_error",
    "youtube_channel_missing", "youtube_connect_failed", "youtube_config_unavailable",
    "streaming_not_connected", "live_streaming_blocked", "streaming_reconnect_required",
    "streaming_prepare_failed", "broadcast_stopped", "broadcast_not_ready",
    "session_already_exists", "session_not_found", "session_not_ready" -> code
    else -> "unrecognized"
}
