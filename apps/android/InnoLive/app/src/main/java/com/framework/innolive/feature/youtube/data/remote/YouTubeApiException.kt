package com.framework.innolive.feature.youtube

import java.io.IOException

class YouTubeApiException(
    val statusCode: Int?,
    operation: String,
    cause: Throwable? = null,
) : IOException(
    if (statusCode == null) "$operation request failed."
    else "$operation request failed with HTTP $statusCode.",
    cause,
)
