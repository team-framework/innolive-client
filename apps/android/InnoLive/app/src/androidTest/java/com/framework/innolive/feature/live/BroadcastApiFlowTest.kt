package com.framework.innolive.feature.live

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// 제품의 요청 생성·응답 처리·작업 큐를 실행한다. 네트워크와 미디어 연결은 검증하지 않는다.
class BroadcastApiFlowTest {
    private val settings = BroadcastSettings("검증", "검증 설명", "private", false, "22")
    private val accessToken = accessTokenFor("broadcast-api-user")

    @Test fun offBroadcastCanGoLiveToggleRecoverFromFailureAndStopWithoutDeletingSession() {
        Harness().use { h ->
            h.patch(false, AnonymizationState.DISABLED)
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            assertFalse(h.requests.any { it.url.encodedPath.endsWith("golive") })
            assertFalse(h.connection.prepareBroadcast(settings))
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            h.connection.resumeBroadcast()
            h.awaitState(BroadcastState.LIVE)
            h.patch(true, AnonymizationState.ENABLED)
            h.patch(false, AnonymizationState.DISABLED)
            val statesBeforeFailure = h.states.toList()
            h.patchStatus = 503
            val failed = h.patchResult(true)
            assertNull(failed.first)
            assertNotNull(failed.second)
            assertEquals(statesBeforeFailure, h.states.toList())
            h.patchStatus = 200
            h.patch(true, AnonymizationState.ENABLED)
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.IDLE)
            assertFalse(h.requests.any { it.method == "DELETE" })
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.IDLE)
            assertEquals(2, h.requests.count { it.url.encodedPath.endsWith("stream/prepare") })
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/golive") })
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/pause") })
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/resume") })
            val save = h.requests.first { it.method == "PUT" }
            val payload = JSONObject(h.body(save))
            assertEquals("private", payload.getString("privacy"))
            assertFalse(payload.getBoolean("made_for_kids"))
            for (request in h.requests) {
                assertEquals("Bearer $accessToken", request.header("Authorization"))
                assertEquals("test-owner", request.header("X-Session-Owner-Token"))
                assertTrue(request.url.encodedPath.startsWith("/sessions/test-session/"))
            }
        }
    }

    @Test fun pauseAndResumeFailureKeepTheLastUsableBroadcastState() {
        Harness().use { h ->
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)

            h.pauseStatus = 500
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.LIVE)

            h.pauseStatus = 200
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            h.resumeStatus = 500
            h.connection.resumeBroadcast()
            h.awaitState(BroadcastState.PAUSED)
        }
    }

    @Test fun failedSettingsSaveDoesNotPrepareAndExplicitRetrySucceeds() {
        Harness().use { h ->
            h.settingsStatus = 500
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.FAILED)
            assertEquals(listOf("PUT"), h.requests.map { it.method })
            h.settingsStatus = 200
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            assertEquals(listOf("broadcast", "broadcast", "prepare"), h.requests.map { it.url.pathSegments.last() })
        }
    }

    @Test fun notReadyGoLiveRetriesAndStopFailureDoesNotReportIdle() {
        Harness().use { h ->
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            h.goLiveNotReady = true
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)
            assertEquals(2, h.requests.count { it.url.encodedPath.endsWith("golive") })
            h.stopStatus = 500
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.FAILED)
            assertFalse(h.states.contains(BroadcastState.IDLE))
        }
    }

    @Test fun completedStateCallbackCanStartTheNextBroadcastOperation() {
        lateinit var h: Harness
        h = Harness(Executor { it.run() }) { state ->
            when (state) {
                BroadcastState.PREPARED -> h.connection.goLive()
                BroadcastState.LIVE -> h.connection.stopBroadcast()
                else -> Unit
            }
        }
        h.use {
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.IDLE)
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/golive") })
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/stop") })
        }
    }

    @Test fun closeDeletesOnlyItsSessionOnceAndCompletesEvenWhenServerReturns404() {
        val h = Harness()
        h.deleteStatus = 404
        h.close()
        h.close()
        val deletes = h.requests.filter { it.method == "DELETE" }
        assertEquals(1, deletes.size)
        assertEquals("/sessions/test-session", deletes.single().url.encodedPath)
        assertEquals("test-owner", deletes.single().header("X-Session-Owner-Token"))
        assertFalse(h.connection.prepareBroadcast(settings))
    }

    private class Harness(
        private val broadcastCallbackExecutor: Executor? = null,
        private val onBroadcastState: (BroadcastState) -> Unit = {},
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val states = CopyOnWriteArrayList<BroadcastState>()
        private val events = LinkedBlockingQueue<BroadcastState>()
        @Volatile var patchStatus = 200
        @Volatile var settingsStatus = 200
        @Volatile var stopStatus = 200
        @Volatile var pauseStatus = 200
        @Volatile var resumeStatus = 200
        @Volatile var deleteStatus = 204
        @Volatile var goLiveNotReady = false
        val connection = WebRtcConnection(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            serverUrl = "https://example.test",
            accessToken = accessTokenFor("broadcast-api-user"),
            initialAnonymizationEnabled = false,
            preferredAudioInput = null,
            onStateChanged = { _, _ -> }, onRemoteTrackChanged = {},
            onLocalMediaReady = { _, _ -> }, onLocalMediaCleared = {},
            onBroadcastStateChanged = { state, _ ->
                states.add(state)
                events.add(state)
                onBroadcastState(state)
            },
            onAnonymizationStateConfirmed = {},
            broadcastCallbackExecutor = broadcastCallbackExecutor,
        )

        init {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                var payload = "{}"
                val status = when {
                    request.method == "DELETE" -> deleteStatus
                    request.method == "PUT" -> settingsStatus
                    request.method == "PATCH" -> {
                        val enabled = JSONObject(body(request)).getBoolean("enabled")
                        payload = """{"session_id":"test-session","media":{"anonymization_enabled":$enabled}}"""
                        patchStatus
                    }
                    request.url.encodedPath.endsWith("golive") && goLiveNotReady -> {
                        goLiveNotReady = false
                        payload = """{"error":{"code":"broadcast_not_ready","message":"not ready"}}"""
                        409
                    }
                    request.url.encodedPath.endsWith("stop") -> stopStatus
                    request.url.encodedPath.endsWith("pause") -> pauseStatus
                    request.url.encodedPath.endsWith("resume") -> resumeStatus
                    else -> 200
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fixture").body(payload.toResponseBody()).build()
            }.build()
            // 테스트에서만 연결 완료 세션과 HTTP 응답을 주입한다. 실제 DNS나 외부 계정은 사용하지 않는다.
            field("httpClient", client)
            field("session", CreatedSession("test-session", "test-owner", AnonymizationState.DISABLED))
        }

        private fun field(name: String, value: Any) {
            WebRtcConnection::class.java.getDeclaredField(name).apply { isAccessible = true; set(connection, value) }
        }

        fun body(request: Request): String = Buffer().also { request.body?.writeTo(it) }.readUtf8()

        fun awaitState(expected: BroadcastState) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "상태 대기 시간 초과: $expected, 실제 $states" }
                val next = events.poll(remaining, TimeUnit.NANOSECONDS)
                assertNotNull("상태 콜백 없음: $expected", next)
                if (next == expected) return
                assertNotEquals("예상하지 않은 실패: $expected", BroadcastState.FAILED, next)
            }
        }

        fun patchResult(enabled: Boolean): Pair<AnonymizationState?, String?> {
            val completed = CountDownLatch(1)
            var result: Pair<AnonymizationState?, String?>? = null
            connection.setAnonymizationEnabled(enabled) { state, error ->
                result = state to error
                completed.countDown()
            }
            assertTrue("PATCH 응답 대기", completed.await(10, TimeUnit.SECONDS))
            return checkNotNull(result)
        }

        fun patch(enabled: Boolean, expected: AnonymizationState) {
            val result = patchResult(enabled)
            assertEquals(expected, result.first)
            assertNull(result.second)
        }

        override fun close() {
            val closed = CountDownLatch(1)
            connection.close { closed.countDown() }
            assertTrue("연결 정리 대기", closed.await(10, TimeUnit.SECONDS))
        }
    }

    private companion object {
        fun accessTokenFor(user: String): String {
            val payload = Base64.encodeToString(
                "{\"sub\":\"$user\"}".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            return "header.$payload.signature"
        }
    }
}
