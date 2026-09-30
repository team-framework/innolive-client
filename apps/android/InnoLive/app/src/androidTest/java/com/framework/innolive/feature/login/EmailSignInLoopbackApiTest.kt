package com.framework.innolive.feature.login

import android.security.NetworkSecurityPolicy
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Sends real loopback HTTP requests through the app's OkHttp API without a remote account. */
class EmailSignInLoopbackApiTest {
    @Test fun debugAppPermitsOnlyLoopbackCleartext() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertEquals(true, policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }

    @Test fun successRequestNormalizesEmailAndPersistsOnlyTheValidatedResponse() {
        val request = withServer(
            200,
            """{"access_token":"access","refresh_token":"refresh","token_type":"Bearer","expires_in":3600,"refresh_expires_in":7200}""",
        ) { endpoint ->
            var saved = 0
            runBlocking {
                withTimeout(5_000) {
                    authenticateAndSaveEmailSession(
                        " Member@Example.com ", " password with spaces ",
                        EmailSignInApi(endpoint = { endpoint })::authenticate,
                    ) { session ->
                        saved++
                        assertEquals("access", session.accessToken)
                        assertEquals("member@example.com", session.profileEmail)
                    }
                }
            }
            assertEquals(1, saved)
        }
        assertEquals("POST /auth/sign-in HTTP/1.1", request.line)
        assertEquals("application/json; charset=utf-8", request.headers["content-type"])
        assertNull(request.headers["authorization"])
        val payload = JSONObject(request.body)
        assertEquals(setOf("email", "password"), payload.keys().asSequence().toSet())
        assertEquals("member@example.com", payload.getString("email"))
        assertEquals(" password with spaces ", payload.getString("password"))
    }

    @Test fun unauthorizedResponseCannotSaveOrExposeServerBody() {
        val request = withServer(
            401,
            "private diagnostic from test server",
        ) { endpoint ->
            var saved = false
            val error = assertThrows(EmailSignInException::class.java) {
                runBlocking {
                    withTimeout(5_000) {
                        authenticateAndSaveEmailSession(
                            "member@example.com", "password",
                            EmailSignInApi(endpoint = { endpoint })::authenticate,
                        ) { saved = true }
                    }
                }
            }
            assertEquals(UiText.Resource(R.string.error_email_credentials), error.error)
            assertFalse(saved)
        }
        assertEquals("POST /auth/sign-in HTTP/1.1", request.line)
    }

    @Test fun malformedSuccessBodyCannotCreateASession() {
        val request = withServer(200, "{}") { endpoint ->
            var saved = false
            assertMalformedBodyFailure {
                runBlocking {
                    withTimeout(5_000) {
                        authenticateAndSaveEmailSession(
                            "member@example.com", "password",
                            EmailSignInApi(endpoint = { endpoint })::authenticate,
                        ) { saved = true }
                    }
                }
            }
            assertFalse(saved)
        }
        assertEquals("POST /auth/sign-in HTTP/1.1", request.line)
    }

    @Test fun malformedBodyAssertionRejectsCancellation() {
        val failure = assertThrows(AssertionError::class.java) {
            assertMalformedBodyFailure { throw CancellationException("simulated timeout") }
        }
        assertEquals("Timed out before the response body was validated", failure.message)
    }

    private fun assertMalformedBodyFailure(action: () -> Unit) {
        val error = assertThrows(IllegalStateException::class.java, action)
        assertFalse("Timed out before the response body was validated", error is CancellationException)
        assertEquals("인증 응답의 토큰이 올바르지 않습니다.", error.message)
    }

    private data class CapturedRequest(
        val line: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun withServer(
        status: Int,
        responseBody: String,
        action: (String) -> Unit,
    ): CapturedRequest {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val captured = executor.submit<CapturedRequest> {
                    server.accept().use { socket -> respond(socket, status, responseBody) }
                }
                val endpoint = "http://127.0.0.1:${server.localPort}/auth/sign-in"
                // Request capture is fulfilled by the server thread before the API returns.
                action(endpoint)
                return captured.get(5, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun respond(socket: Socket, status: Int, body: String): CapturedRequest {
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
        val requestLine = line()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            check(separator > 0)
            headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            check(count > 0) { "HTTP request body ended early" }
            offset += count
        }
        val response = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status ${if (status == 200) "OK" else "Unauthorized"}\r\n".toByteArray())
            write("Content-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(response)
            flush()
        }
        return CapturedRequest(requestLine, headers, bytes.toString(Charsets.UTF_8))
    }
}
