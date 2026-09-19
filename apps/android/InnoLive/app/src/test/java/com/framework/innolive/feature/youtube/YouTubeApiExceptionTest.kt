package com.framework.innolive.feature.youtube

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
        )

        assertEquals("youtube_channel_missing", exception.errorCode)
        assertEquals(
            "선택한 Google 계정에 YouTube 채널이 없습니다. 채널을 만든 뒤 다시 시도하거나 다른 계정을 선택해 주세요.",
            youtubeConnectionFailureMessage(exception),
        )
    }

    @Test
    fun unknownOrMalformedErrorsUseGenericMessageWithoutServerDetails() {
        val expected = "YouTube 계정 연동에 실패했습니다. 다시 시도해 주세요."

        assertNull(parseYouTubeApiErrorCode("private server details"))
        assertEquals(expected, youtubeConnectionFailureMessage(null))
        assertEquals(
            expected,
            youtubeConnectionFailureMessage(
                YouTubeApiException(
                    statusCode = 500,
                    operation = "YouTube account connection",
                    errorCode = parseYouTubeApiErrorCode(
                        """{"error":{"code":"internal_error","message":"private server details"}}""",
                    ),
                ),
            ),
        )
    }
}
