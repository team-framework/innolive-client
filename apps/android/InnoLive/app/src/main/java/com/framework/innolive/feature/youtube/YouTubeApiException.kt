package com.framework.innolive.feature.youtube

import java.io.IOException
import org.json.JSONObject

class YouTubeApiException(
    val statusCode: Int?,
    operation: String,
    val errorCode: String? = null,
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

internal fun youtubeConnectionFailureMessage(exception: Throwable?): String =
    when ((exception as? YouTubeApiException)?.errorCode) {
        "youtube_channel_missing" ->
            "선택한 Google 계정에 YouTube 채널이 없습니다. 채널을 만든 뒤 다시 시도하거나 다른 계정을 선택해 주세요."
        else -> "YouTube 계정 연동에 실패했습니다. 다시 시도해 주세요."
    }
