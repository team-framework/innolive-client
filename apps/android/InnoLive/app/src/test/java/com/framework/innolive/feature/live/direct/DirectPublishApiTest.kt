package com.framework.innolive.feature.live.direct

import com.framework.innolive.feature.live.AnonymizationState
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.ServerApiException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class DirectPublishApiTest {
    private val session = CreatedSession("session-1", "owner-1", AnonymizationState.DISABLED, BroadcastProvider.YOUTUBE)
    private val requests = mutableListOf<Request>()
    private val responses = ArrayDeque<(Request) -> Response>()
    private val logs = mutableListOf<String>()
    private var refreshCount = 0
    private var refreshFailure: Exception? = null

    private val api = DirectPublishApi(
        serverUrl = "https://api.example.test/",
        accessToken = "access-1",
        refreshAccessToken = {
            refreshCount++
            refreshFailure?.let { throw it }
            "access-2"
        },
        client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            responses.removeFirst()(request)
        }).build(),
        log = logs::add,
    )

    private fun respond(code: Int, body: String = "{}") {
        responses += { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun fail(exception: IOException) {
        responses += { throw exception }
    }

    private fun error(code: String, field: String? = null): String = JSONObject()
        .put("error", JSONObject().put("code", code).put("message", "m").apply {
            if (field != null) put("details", JSONObject().put("field", field))
        }).toString()

    private fun Request.json(): JSONObject = JSONObject(Buffer().also { checkNotNull(body).writeTo(it) }.readUtf8())

    private val heartbeat = DirectHeartbeat(
        sentAt = Instant.parse("2026-10-12T08:30:00Z"),
        targets = listOf(HeartbeatTarget(BroadcastProvider.YOUTUBE, connected = true)),
        video = HeartbeatVideo(fps = 30.0, droppedFrames = 0),
        anonymization = HeartbeatAnonymization(failedFrames = 0),
        model = "Pixel 8",
    )

    @Test fun createSessionRequestsOnDeviceSessionWithResolution() {
        respond(201, """{"session_id":"s","owner_token":"o","provider":"chzzk","ai_processing":"on_device","media":{"anonymization_enabled":false}}""")
        val created = api.createSession(BroadcastProvider.CHZZK, DirectPublishResolution.FHD)
        assertEquals("s", created.sessionId)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/sessions", request.url.encodedPath)
        assertEquals("Bearer access-1", request.header("Authorization"))
        val body = request.json()
        assertEquals("chzzk", body.getString("provider"))
        assertEquals("on_device", body.getString("ai_processing"))
        assertEquals("fhd", body.getString("broadcast_resolution"))
    }

    @Test fun createSessionRejectsServerModeSessions() {
        respond(201, """{"session_id":"s","owner_token":"o","provider":"youtube","ai_processing":"server"}""")
        assertThrows(IllegalStateException::class.java) {
            api.createSession(BroadcastProvider.YOUTUBE, DirectPublishResolution.HD_720)
        }
    }

    @Test fun createSessionPassesExistingSessionConflictToCaller() {
        respond(409, error("session_already_exists"))
        val exception = assertThrows(ServerApiException::class.java) {
            api.createSession(BroadcastProvider.YOUTUBE, DirectPublishResolution.HD_720)
        }
        assertEquals("session_already_exists", exception.code)
        assertEquals(409, exception.statusCode)
    }

    @Test fun sessionRequestsCarryOwnerTokenAndProvider() {
        respond(200, """{"stream":{"broadcast_phase":"idle"},"targets":[{"provider":"chzzk","stream":{"broadcast_phase":"idle"}}]}""")
        assertEquals(BroadcastState.IDLE, api.stop(session, BroadcastProvider.CHZZK))
        val request = requests.single()
        assertEquals("/sessions/session-1/stream/stop", request.url.encodedPath)
        assertEquals("chzzk", request.url.queryParameter("provider"))
        assertEquals("owner-1", request.header("X-Session-Owner-Token"))
    }

    @Test fun prepareSendsProviderAndConcurrentConfirmation() {
        respond(200, """{"stream":{"broadcast_phase":"prepared"},"ingest_url":"rtmps://h/live2/secret-key"}""")
        val result = api.prepare(session, BroadcastProvider.YOUTUBE, allowConcurrent = true)
        assertEquals("rtmps://h/live2/secret-key", result.ingestUrl?.reveal())
        val body = requests.single().json()
        assertEquals("youtube", body.getString("provider"))
        assertTrue(body.getBoolean("allow_concurrent"))
        assertFalse(logs.any { it.contains("secret-key") })
    }

    @Test fun goLiveNotReadyIsLeftToCallerRetry() {
        respond(409, error("broadcast_not_ready"))
        val exception = assertThrows(ServerApiException::class.java) { api.goLive(session, BroadcastProvider.YOUTUBE) }
        assertEquals("broadcast_not_ready", exception.code)
        assertEquals(1, requests.size)
    }

    @Test fun goLiveLogsOnlyOperationAndStatus() {
        respond(200, """{"broadcast_phase":"live","direct_ingest":[{"provider":"chzzk","ingest_url":"rtmp://h/relay/secret-key"}]}""")
        api.goLive(session, BroadcastProvider.CHZZK)
        assertEquals(listOf("operation=stream_golive status=200"), logs)
    }

    @Test fun unauthorizedRequestRefreshesOnceAndRetries() {
        respond(401)
        respond(200, """{"session":"ok"}""")
        api.saveYouTubeSettings(session, BroadcastSettings("t", "", "unlisted", false, "22"))
        assertEquals(1, refreshCount)
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), requests.map { it.header("Authorization") })
        assertEquals("PUT", requests.last().method)
    }

    @Test fun failedRefreshIsUnauthorized() {
        refreshFailure = IOException("revoked")
        respond(401)
        val exception = assertThrows(ServerApiException::class.java) { api.stop(session, BroadcastProvider.YOUTUBE) }
        assertEquals("unauthorized", exception.code)
    }

    @Test fun deleteTreatsMissingOrForeignSessionAsCleaned() {
        respond(404)
        api.deleteSession(session)
        respond(403)
        api.deleteSession(session)
        respond(500, error("internal_error"))
        assertThrows(ServerApiException::class.java) { api.deleteSession(session) }
        assertTrue(requests.all { it.method == "DELETE" && it.url.encodedPath == "/sessions/session-1" })
    }

    @Test fun heartbeatAcceptedCarriesStopTargets() {
        respond(200, """{"stop_targets":["chzzk"],"notices":[],"broadcast_remaining_seconds":60}""")
        val result = api.heartbeat(session, heartbeat)
        assertEquals(HeartbeatResult.Accepted(listOf(BroadcastProvider.CHZZK), emptyList(), 60), result)
        val request = requests.single()
        assertEquals("/sessions/session-1/heartbeat", request.url.encodedPath)
        assertEquals("android", request.json().getString("platform"))
    }

    @Test fun heartbeatMapsStatusesToStreamingDecisions() {
        val cases = listOf(
            404 to "{}" to HeartbeatResult.StopAll(HeartbeatStopReason.SESSION_GONE),
            403 to error("forbidden") to HeartbeatResult.StopAll(HeartbeatStopReason.OWNERSHIP_LOST),
            409 to error("heartbeat_not_applicable") to HeartbeatResult.StopAll(HeartbeatStopReason.NOT_APPLICABLE),
            409 to error("withdrawal_in_progress") to HeartbeatResult.RetryLater,
            400 to error("bad_request", "video.fps") to HeartbeatResult.Invalid("video.fps"),
            500 to error("internal_error") to HeartbeatResult.RetryLater,
            503 to "<html>" to HeartbeatResult.RetryLater,
        )
        for ((response, expected) in cases) {
            respond(response.first, response.second)
            assertEquals("HTTP ${response.first}", expected, api.heartbeat(session, heartbeat))
        }
    }

    @Test fun heartbeatKeepsStreamingOnNetworkAndAuthFailures() {
        fail(IOException("timeout"))
        assertEquals(HeartbeatResult.RetryLater, api.heartbeat(session, heartbeat))

        refreshFailure = IOException("revoked")
        respond(401)
        assertEquals(HeartbeatResult.Unauthorized, api.heartbeat(session, heartbeat))

        refreshFailure = null
        respond(401)
        respond(401)
        assertEquals(HeartbeatResult.Unauthorized, api.heartbeat(session, heartbeat))
    }

    @Test fun rejectsCleartextServer() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectPublishApi("http://api.example.test", "token", { "token" }, log = {})
        }
    }
}
