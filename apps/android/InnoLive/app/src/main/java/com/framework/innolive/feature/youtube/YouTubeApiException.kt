package com.framework.innolive.feature.youtube

import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.serverErrorGuidance
import java.io.IOException
import org.json.JSONObject

class YouTubeApiException(
    val statusCode: Int?,
    operation: String,
    val errorCode: String? = null,
    val serverMessage: String? = null,
    cause: Throwable? = null,
    val helpUrl: String? = null,
    val field: String? = null,
    val reason: String? = null,
) : IOException(
    if (statusCode == null) "$operation request failed."
    else "$operation request failed with HTTP $statusCode.",
    cause,
)

internal fun parseYouTubeApiErrorCode(body: String): String? = runCatching {
    JSONObject(body)
        .optJSONObject("error")
        ?.optString("code")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}.getOrNull()

internal fun parseYouTubeApiErrorMessage(body: String): String? {
    val trimmedBody = body.trim()
    if (trimmedBody.isEmpty()) return null

    val payload = runCatching { JSONObject(trimmedBody) }.getOrElse {
        // 서버가 API 오류 형식 대신 일반 텍스트 오류를 의도적으로 돌려줄 수 있습니다.
        // 외부 내용이므로 그대로 보존합니다.
        return trimmedBody
    }
    return payload
        .optJSONObject("error")
        ?.optString("message")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: payload.optString("message")
            .trim()
            .takeIf(String::isNotEmpty)
}

internal fun youtubeConnectionFailureMessage(exception: Throwable?): UiText =
    youtubeFailureGuidance(exception)?.message ?: when ((exception as? YouTubeApiException)?.errorCode) {
        "youtube_channel_missing" ->
            UiText.Resource(R.string.error_youtube_channel_missing)
        else -> UiText.Resource(R.string.error_youtube_connection)
    }

internal fun youtubeFailureGuidance(exception: Throwable?): ServerErrorGuidance? =
    (exception as? YouTubeApiException)?.let {
        serverErrorGuidance(it.errorCode, it.statusCode, it.helpUrl, it.field, it.reason)
    }

internal fun parseYouTubeApiErrorDetail(body: String, key: String): String? = runCatching {
    (JSONObject(body).optJSONObject("error")?.optJSONObject("details")?.opt(key) as? String)
        ?.takeIf { it.isNotBlank() }
}.getOrNull()
