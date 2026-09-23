package com.framework.innolive.feature.youtube

import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeApiExceptionTest {
    @Test
    fun channelMissingResponseSuggestsCreatingChannelOrChoosingAnotherAccount() {
        val body = """{"error":{"code":"youtube_channel_missing","message":"private details"}}"""
        val exception = YouTubeApiException(
            statusCode = 422,
            operation = "YouTube account connection",
            errorCode = parseYouTubeApiErrorCode(body),
            serverMessage = parseYouTubeApiErrorMessage(body),
        )

        assertEquals("youtube_channel_missing", exception.errorCode)
        assertEquals(
            UiText.Resource(R.string.error_youtube_channel_missing),
            youtubeConnectionFailureMessage(exception),
        )
    }

    @Test
    fun unknownErrorsNeverDisplayJsonOrPlainTextServerMessages() {
        val expected = UiText.Resource(R.string.error_youtube_connection)

        assertNull(parseYouTubeApiErrorCode("private server details"))
        assertEquals(expected, youtubeConnectionFailureMessage(null))
        assertEquals("private server details", parseYouTubeApiErrorMessage("private server details"))
        assertEquals(
            expected,
            youtubeConnectionFailureMessage(
                YouTubeApiException(
                    statusCode = 500,
                    operation = "YouTube account connection",
                    errorCode = parseYouTubeApiErrorCode(
                        """{"error":{"code":"internal_error","message":"private server details"}}""",
                    ),
                    serverMessage = parseYouTubeApiErrorMessage(
                        """{"error":{"code":"internal_error","message":"private server details"}}""",
                    ),
                ),
            ),
        )
        assertEquals(
            expected,
            youtubeConnectionFailureMessage(
                YouTubeApiException(
                    statusCode = 500,
                    operation = "YouTube account connection",
                    serverMessage = parseYouTubeApiErrorMessage("<html>private diagnostics</html>"),
                ),
            ),
        )
    }
}
