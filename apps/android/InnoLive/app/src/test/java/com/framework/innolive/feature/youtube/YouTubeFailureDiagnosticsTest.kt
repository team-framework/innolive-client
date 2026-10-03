package com.framework.innolive.feature.youtube

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

class YouTubeFailureDiagnosticsTest {
    @Test
    fun googleFailureRetainsStatusWithoutAccountOrExceptionMessage() {
        val diagnostic = youtubeFailureDiagnostic(ApiException(Status(10, "private-account@example.com")))
        assertEquals("source=google status=10", diagnostic)
    }

    @Test
    fun serverFailureRetainsRecognizedCodeWithoutResponseDetails() {
        val diagnostic = youtubeFailureDiagnostic(YouTubeApiException(
            statusCode = 422,
            operation = "YouTube account connection",
            errorCode = "youtube_channel_missing",
            serverMessage = "private-account@example.com authorization-code",
        ))
        assertEquals("source=server status=422 code=youtube_channel_missing cause=null", diagnostic)
        assertFalse(diagnostic.contains("private-account"))
        assertFalse(diagnostic.contains("authorization-code"))
    }

    @Test
    fun arbitraryServerCodesAndTransportMessagesAreRedacted() {
        val diagnostic = youtubeFailureDiagnostic(YouTubeApiException(
            statusCode = null,
            operation = "YouTube account connection",
            errorCode = "private-token",
            cause = IOException("private authorization headers"),
        ))
        assertEquals("source=server status=null code=unrecognized cause=IOException", diagnostic)
        assertEquals("absent", safeServerErrorCode(null))
        assertFalse(youtubeFailureDiagnostic(IllegalStateException("private-token")).contains("private-token"))
    }
}
