package com.framework.innolive.feature.live

import com.framework.innolive.R
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.serverErrorGuidance
import com.framework.innolive.feature.youtube.YouTubeApiException
import com.framework.innolive.feature.youtube.retryYouTubeUnauthorized
import com.framework.innolive.feature.youtube.parseYouTubeApiErrorDetail
import com.framework.innolive.feature.youtube.youtubeFailureGuidance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerErrorGuidanceTest {
    @Test fun apiEnvelopePreservesFieldDetailsAndProvidesLocalizedRecovery() {
        val error = parseServerApiException("""{"error":{"code":"bad_request","message":"private details",
            "details":{"field":"title","reason":"required"}}}""", 400)
        val guidance = checkNotNull(error.guidance())
        assertEquals("title", guidance.field)
        assertEquals(UiText.Resource(R.string.error_field_required), guidance.message)
        assertEquals(ServerErrorAction.EDIT_SETTINGS, guidance.action)
        assertEquals("required", error.reason)
    }

    @Test fun blockedStreamingUsesHttpsHelpOnlyWithoutCredentials() {
        val body = """{"error":{"code":"live_streaming_blocked","details":{"help_url":"https://support.google.com/youtube"}}}"""
        val error = parseServerApiException(body, 403)
        assertEquals(ServerErrorAction.HELP, error.guidance()!!.action)
        assertEquals(error.helpUrl, parseYouTubeApiErrorDetail(body, "help_url"))
        for (url in listOf("http://example.test", "javascript:alert(1)", "https://token@example.test", "broken")) {
            val guidance = serverErrorGuidance("live_streaming_blocked", helpUrl = url)!!
            assertNull(guidance.helpUrl)
            assertEquals(ServerErrorAction.NONE, guidance.action)
        }
    }

    @Test fun quotaAndAuthenticationErrorsSurviveCommonCatchWithoutServerText() {
        val quota = parseServerApiException("""{"error":{"code":"streaming_quota_exceeded","message":"upstream details"}}""", 503)
        assertEquals(UiText.Resource(R.string.error_streaming_quota), quota.guidance()!!.message)
        assertEquals(UiText.Resource(R.string.error_authentication_expired), parseServerApiException("not JSON", 401).guidance()!!.message)
        assertEquals(ServerErrorAction.LOGIN, youtubeFailureGuidance(YouTubeApiException(401, "fixture"))!!.action)
    }

    @Test fun malformedAndUnknownErrorsKeepTheExistingFallbackAvailable() {
        assertNull(parseServerApiException("broken").guidance())
        assertNull(parseServerApiException("""{"error":{"code":true,"details":{"field":7}}}""").field)
        assertNull(serverErrorGuidance("new_server_error"))
        assertNull(parseYouTubeApiErrorDetail("""{"error":{"details":{"field":7}}}""", "field"))
    }

    @Test fun busyPauseAndStopKeepTheBroadcastClockUntilPollingConfirmsState() {
        for (state in listOf(BroadcastState.PAUSING, BroadcastState.RESUMING, BroadcastState.STOPPING)) {
            val next = broadcastStateAfterServerError("broadcast_busy", BroadcastState.FAILED, state)
            assertEquals(state, next)
            assertEquals(1_000L, nextBroadcastStartedAt(1_000L, next, 9_000L))
        }
        assertEquals(BroadcastState.GOING_LIVE, broadcastStateAfterServerError("broadcast_going_live", BroadcastState.PREPARED))
        assertEquals(BroadcastState.PREPARED, broadcastStateAfterServerError("streaming_golive_failed", BroadcastState.PREPARED))
    }

    @Test fun youtube401RefreshesOnceAndUsesTheNewToken() = runBlocking {
        val tokens = mutableListOf<String>()
        var refreshes = 0
        val result = retryYouTubeUnauthorized("old", { refreshes++; "new" }) { token ->
            tokens += token
            if (token == "old") throw YouTubeApiException(401, "fixture")
            "connected"
        }
        assertEquals("connected", result)
        assertEquals(listOf("old", "new"), tokens)
        assertEquals(1, refreshes)
    }

    @Test fun youtubeSecond401StopsAndOtherStatusesNeverRefresh() = runBlocking {
        for (status in listOf(401, 403, 429, 503)) {
            var requests = 0
            var refreshes = 0
            try {
                retryYouTubeUnauthorized("old", { refreshes++; "new" }) {
                    requests++
                    throw YouTubeApiException(status, "fixture")
                }
                fail("Expected failure")
            } catch (exception: YouTubeApiException) {
                assertEquals(status, exception.statusCode)
                assertEquals(if (status == 401) 2 else 1, requests)
                assertEquals(if (status == 401) 1 else 0, refreshes)
            }
        }
    }
}
