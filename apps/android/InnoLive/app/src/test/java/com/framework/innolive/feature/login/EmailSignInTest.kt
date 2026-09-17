package com.framework.innolive.feature.login

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmailSignInTest {
    private val valid = """{"access_token":"access","refresh_token":"refresh","token_type":"Bearer","expires_in":3600,"refresh_expires_in":7200}"""

    @Test
    fun sendsOnlyEmailAndUnmodifiedPasswordThenSavesCompleteSession() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("/auth/sign-in", chain.request().url.encodedPath)
            assertEquals("POST", chain.request().method)
            assertNull(chain.request().header("Authorization"))
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            assertEquals(setOf("email", "password"), json.keys().asSequence().toSet())
            assertEquals("member@example.com", json.getString("email"))
            assertEquals(" password with spaces ", json.getString("password"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(valid.toResponseBody()).build()
        }.build()
        val api = EmailSignInApi(client) { "https://example.test/auth/sign-in" }
        var saves = 0
        authenticateAndSaveEmailSession(" member@example.com ", " password with spaces ", api::authenticate) {
            saves++
            assertEquals("access", it.accessToken)
            assertEquals("refresh", it.refreshToken)
            assertEquals("member@example.com", it.profileEmail)
        }
        assertEquals(1, saves)
        assertEquals(1, requests)
    }

    @Test
    fun failuresAreSafeAndDoNotSaveSession() = runBlocking {
        for (code in listOf(401, 429, 503, 500, 302)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("failure").body("private server details".toResponseBody()).build()
            }.build()
            val api = EmailSignInApi(client) { "https://example.test/auth/sign-in" }
            var saved = false
            try {
                authenticateAndSaveEmailSession("member@example.com", "password", api::authenticate) { saved = true }
                fail("로그인 실패 응답은 예외여야 한다")
            } catch (error: EmailSignInException) {
                assertFalse(error.message!!.contains("private server details"))
                if (code == 401) assertTrue(error.message!!.contains("비밀번호"))
                if (code == 429) assertTrue(error.message!!.contains("시도가 많습니다"))
            }
            assertFalse(saved)
        }
    }

    @Test
    fun malformedTokenResponseIsRejected() {
        for (body in listOf("{}", valid.replace("\"access\"", "null"),
            valid.replace("\"refresh\"", "123"), valid.replace("3600", "0"),
            valid.replace("Bearer", "Other"))) {
            assertThrows(Exception::class.java) { parseEmailSession(body, "member@example.com") }
        }
    }

    @Test
    fun lateResultAfterCancellationCannotPersistSession() = runBlocking {
        val response = CompletableDeferred<Unit>()
        var saved = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            authenticateAndSaveEmailSession("member@example.com", "password", { _, _ ->
                withContext(NonCancellable) { response.await() }
                parseEmailSession(valid, "member@example.com")
            }) { saved = true }
        }
        job.cancel()
        response.complete(Unit)
        job.join()
        assertFalse(saved)
    }
}
