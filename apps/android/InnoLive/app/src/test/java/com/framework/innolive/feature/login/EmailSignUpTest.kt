package com.framework.innolive.feature.login

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmailSignUpTest {
    @Test
    fun nativeSignupAndVerificationUseJsonTokenWithoutCookieOrLogin() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths.add(request.url.encodedPath)
            assertEquals("POST", request.method)
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = JSONObject(body)
            val response = if (paths.size == 1) {
                assertEquals(setOf("email", "password"), json.keys().asSequence().toSet())
                assertEquals("member@example.com", json.getString("email"))
                assertEquals(" password ", json.getString("password"))
                """{"status":"verification_email_sent","signup_token":"signup-secret"}"""
            } else {
                assertEquals(setOf("signup_token", "verification_code"), json.keys().asSequence().toSet())
                assertEquals("signup-secret", json.getString("signup_token"))
                assertEquals("012345", json.getString("verification_code"))
                """{"status":"email_verified"}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(response.toResponseBody()).build()
        }.build()
        val api = EmailSignUpApi(client) { "https://example.test" }
        val token = api.signUp(" Member@Example.com ", " password ")
        api.verify(token, "012345")
        assertEquals(listOf("/auth/native/sign-up", "/auth/native/verify-email"), paths)
    }

    @Test
    fun serverErrorCodesUseTheSameSignupMessagesAsIos() = runBlocking {
        val cases = mapOf(
            "email_already_registered" to "이미 가입된 이메일입니다. 로그인해 주세요.",
            "invalid_verification_code" to "인증 코드가 올바르지 않거나 만료됐습니다.",
            "invalid_signup_token" to "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요.",
            "email_delivery_failed" to "인증 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요.",
        )

        for ((code, expected) in cases) {
            val body = JSONObject()
                .put("error", JSONObject().put("code", code).put("message", "private details"))
                .toString()
            val error = assertThrows(EmailSignUpException::class.java) {
                runBlocking { api(400, body).verify("token", "123456") }
            }
            assertEquals(expected, error.message)
            assertFalse(error.message!!.contains("private details"))
        }
    }

    @Test
    fun serverErrorsNeverBecomeSuccessfulSignupOrExposeServerBody() = runBlocking {
        for (status in listOf(400, 409, 429, 502, 503, 500, 302)) {
            val api = api(status, "private server details")
            for (verify in listOf(false, true)) {
                try {
                    if (verify) api.verify("token", "123456") else api.signUp("member@example.com", "password")
                    fail("실패 응답을 성공으로 처리하면 안 된다")
                } catch (error: EmailSignUpException) {
                    assertFalse(error.message!!.contains("private server details"))
                    if (status == 409) assertTrue(error.message!!.contains("이미 가입"))
                    if (status == 429) assertTrue(error.message!!.contains("요청이 많습니다"))
                    if (verify && status == 400) assertTrue(error.message!!.contains("만료"))
                }
            }
        }
    }

    @Test
    fun incompleteSuccessCannotAdvanceToVerificationOrCompletion() = runBlocking {
        for (body in listOf("{}", """{"status":"verification_email_sent"}""",
            """{"status":"verification_email_sent","signup_token":123}""",
            """{"status":"unknown","signup_token":"token"}""")) {
            try { api(200, body).signUp("member@example.com", "password"); fail() }
            catch (_: IllegalStateException) { }
        }
        try { api(200, "{}").verify("token", "123456"); fail() }
        catch (_: IllegalStateException) { }
    }

    @Test
    fun passwordLimitUsesUtf8BytesAndCodeUsesSixAsciiDigits() = runBlocking {
        assertTrue(isSignUpPasswordValid("가".repeat(24)))
        assertFalse(isSignUpPasswordValid("가".repeat(25)))
        assertFalse(isSignUpPasswordValid("short"))
        assertTrue(isSignUpPasswordValid("a".repeat(72)))
        assertFalse(isSignUpPasswordValid("a".repeat(73)))
        val api = api(200, "{}")
        for (code in listOf("12345", "1234567", "１２３４５６", "abcdef")) {
            try { api.verify("token", code); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun api(code: Int, body: String): EmailSignUpApi = EmailSignUpApi(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody()).build()
        }.build(),
    ) { "https://example.test" }
}
