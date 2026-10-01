package com.framework.innolive.feature.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class YouTubeAccountChangeApiTest {
    @Test
    fun lostReplacementResponseBlocksOldAccountUntilSuccessfulAccountLookup() = runBlocking {
        withServer(listOf(200, 503, 200), loseReplacementResponse = true) { coordinator, requests ->
            val oldAccount = StreamingAccount("youtube", "old-channel", "Old Channel", false)
            val memory = YouTubeVerificationMemory().apply {
                state.value = YouTubeAccountVerificationState.VERIFIED
                verifiedProfileEmail.value = "viewer@example.com"
            }
            val failure = assertThrows(YouTubeApiException::class.java) {
                runBlocking {
                    memory.connectAccount {
                        coordinator.connect("new-code", "initial-token") { "refreshed-token" }
                    }
                }
            }
            assertEquals(null, failure.statusCode)
            assertFalse(hasVerifiedYouTubeAccount(
                oldAccount, memory.state.value, memory.verifiedProfileEmail.value, "viewer@example.com",
            ))
            assertFalse(memory.suppressRefreshOnce.value)

            val lookupFailure = assertThrows(YouTubeApiException::class.java) {
                runBlocking { coordinator.loadAccount { "initial-token" } }
            }
            assertEquals(503, lookupFailure.statusCode)
            assertFalse(hasVerifiedYouTubeAccount(
                oldAccount, memory.state.value, memory.verifiedProfileEmail.value, "viewer@example.com",
            ))

            val refreshedAccount = coordinator.loadAccount { "initial-token" }
            assertEquals(newAccount, refreshedAccount)
            acceptServerVerifiedYouTubeAccount(
                refreshedAccount,
                onVerified = {
                    memory.state.value = YouTubeAccountVerificationState.VERIFIED
                    memory.verifiedProfileEmail.value = "viewer@example.com"
                },
                saveConnection = {},
                removeConnection = {},
            )
            assertTrue(hasVerifiedYouTubeAccount(
                refreshedAccount, memory.state.value, memory.verifiedProfileEmail.value, "viewer@example.com",
            ))
            assertEquals(3, requests.size)
        }
    }

    @Test
    fun successfulReplacementUsesConnectResponseWithoutSecondAccountRequest() = runBlocking {
        withServer(listOf(200)) { coordinator, requests ->
            val account = coordinator.connect("new-code", "initial-token") {
                error("A successful connection must not refresh the token")
            }
            assertEquals(newAccount, account)
            assertEquals(listOf("Bearer initial-token"), requests.map { it.authorization })
        }
    }

    @Test
    fun unauthorizedConnectionRefreshesOnceAndReturnsReplacementWithoutAccountLookup() = runBlocking {
        withServer(listOf(401, 200)) { coordinator, requests ->
            var refreshes = 0
            val account = coordinator.connect("new-code", "initial-token") {
                refreshes++
                "refreshed-token"
            }
            assertEquals(newAccount, account)
            assertEquals(1, refreshes)
            assertEquals(
                listOf("Bearer initial-token", "Bearer refreshed-token"),
                requests.map { it.authorization },
            )
        }
    }

    @Test
    fun secondUnauthorizedResponseIsNotRetried() = runBlocking {
        withServer(listOf(401, 401)) { coordinator, requests ->
            var refreshes = 0
            val failure = assertThrows(YouTubeApiException::class.java) {
                runBlocking {
                    coordinator.connect("new-code", "initial-token") {
                        refreshes++
                        "refreshed-token"
                    }
                }
            }
            assertEquals(401, failure.statusCode)
            assertEquals(1, refreshes)
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun rejectedOAuthCodeIsNotReplayedOrFollowedByAccountLookup() = runBlocking {
        withServer(listOf(422)) { coordinator, requests ->
            val failure = assertThrows(YouTubeApiException::class.java) {
                runBlocking {
                    coordinator.connect("new-code", "initial-token") {
                        error("Only a 401 may refresh the token")
                    }
                }
            }
            assertEquals(422, failure.statusCode)
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun cancelledRefreshDoesNotReplayAuthorizationCode() = runBlocking {
        withServer(listOf(401)) { coordinator, requests ->
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    coordinator.connect("new-code", "initial-token") {
                        throw CancellationException("Account change is no longer allowed")
                    }
                }
            }
            assertEquals(1, requests.size)
        }
    }

    private val newAccount = StreamingAccount(
        "youtube", "new-channel", "새 채널", reconnectRequired = false,
    )

    private data class CapturedRequest(val authorization: String, val body: String)

    /** Exercises the coordinator and production HTTP/parser path against a local test server. */
    private suspend fun withServer(
        statuses: List<Int>,
        loseReplacementResponse: Boolean = false,
        action: suspend (YouTubeAccountCoordinator, List<CapturedRequest>) -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val requests = java.util.concurrent.CopyOnWriteArrayList<CapturedRequest>()
                val responses = executor.submit {
                    statuses.forEachIndexed { index, status ->
                        val isLookup = loseReplacementResponse && index > 0
                        server.accept().use { socket -> respond(socket, status, isLookup) { requests += it } }
                    }
                }
                // Redirect only the injected test client; the production API still requires HTTPS.
                var attempts = 0
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    val attempt = attempts++
                    check(attempt < statuses.size) { "Unexpected extra connection request" }
                    val isLookup = loseReplacementResponse && attempt > 0
                    assertEquals(if (isLookup) "GET" else "POST", request.method)
                    assertEquals(if (isLookup) "/auth/streaming/accounts" else "/auth/youtube/connect", request.url.encodedPath)
                    val response = chain.proceed(
                        request.newBuilder().url(
                            "http://127.0.0.1:${server.localPort}${request.url.encodedPath}",
                        ).build(),
                    )
                    if (loseReplacementResponse && attempt == 0) {
                        // The server has completed the POST; lose only its return path to the app.
                        response.close()
                        throw IOException("Replacement response lost after server applied it")
                    }
                    response
                }.build()
                val api = YouTubeApi("https://example.test")
                YouTubeApi::class.java.getDeclaredField("httpClient").apply {
                    isAccessible = true
                    set(api, client)
                }
                val coordinator = YouTubeAccountCoordinator(null)
                YouTubeAccountCoordinator::class.java.getDeclaredField("api").apply {
                    isAccessible = true
                    set(coordinator, api)
                }
                try {
                    action(coordinator, requests)
                    responses.get(5, TimeUnit.SECONDS)
                    assertEquals(statuses.size, requests.size)
                    val connectRequests = if (loseReplacementResponse) requests.take(1) else requests
                    connectRequests.forEach { request ->
                        val payload = JSONObject(request.body)
                        assertEquals("new-code", payload.getString("server_auth_code"))
                        assertEquals("native", payload.getString("code_source"))
                    }
                } finally {
                    coordinator.close()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun respond(socket: Socket, status: Int, isLookup: Boolean, onCaptured: (CapturedRequest) -> Unit) {
        socket.soTimeout = 5_000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = input.read()
                check(value >= 0) { "HTTP request ended before headers" }
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.add(value.toByte())
            }
            return bytes.toByteArray().toString(Charsets.US_ASCII)
        }
        assertEquals(if (isLookup) "GET /auth/streaming/accounts HTTP/1.1" else "POST /auth/youtube/connect HTTP/1.1", line())
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            check(separator > 0)
            headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
        }
        val body = ByteArray(if (isLookup) 0 else checkNotNull(headers["content-length"]).toInt())
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            check(count > 0) { "HTTP request body ended early" }
            offset += count
        }
        val responseBody = if (status == 200 && isLookup) {
            """[{"provider":"youtube","channel_id":"new-channel","channel_title":"새 채널","reconnect_required":false}]"""
        } else if (status == 200) {
            """{"connected":true,"provider":"youtube","channel":{"id":"new-channel","title":"새 채널"}}"""
        } else {
            """{"error":{"code":"test_rejection","message":"test rejection"}}"""
        }
        val response = responseBody.toByteArray(Charsets.UTF_8)
        // Publish the capture before responding so assertions see every completed request.
        val captured = CapturedRequest(checkNotNull(headers["authorization"]), body.toString(Charsets.UTF_8))
        onCaptured(captured)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Test\r\n".toByteArray())
            write("Content-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(response)
            flush()
        }
    }
}
