package com.framework.innolive.feature.youtube

import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import java.io.IOException
import org.json.JSONObject

class YouTubeApiException(
    val statusCode: Int?,
    operation: String,
    val errorCode: String? = null,
    val serverMessage: String? = null,
    cause: Throwable? = null,
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
        // A server may intentionally return a plain-text error instead of the
        // API error envelope. It is external content, so preserve it verbatim.
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
    when ((exception as? YouTubeApiException)?.errorCode) {
        "youtube_channel_missing" ->
            UiText.Resource(R.string.error_youtube_channel_missing)
        else -> (exception as? YouTubeApiException)?.serverMessage
            ?.takeIf(String::isNotBlank)
            ?.let(UiText::Dynamic)
            ?: UiText.Resource(R.string.error_youtube_connection)
    }
