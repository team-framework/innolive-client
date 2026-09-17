package com.framework.innolive.feature.login

import com.framework.innolive.feature.login.oauth.google.googleAuthEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class EmailSignUpException(message: String) : IOException(message)

internal fun isSignUpPasswordValid(password: String): Boolean =
    password.toByteArray(Charsets.UTF_8).size in 8..72

internal class EmailSignUpApi(
    private val calls: Call.Factory = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: () -> String = {
        googleAuthEndpoint().toString().removeSuffix("/auth/google")
    },
) {
    suspend fun signUp(email: String, password: String): String {
        val normalizedEmail = normalizeEmail(email)
        require(normalizedEmail.isNotEmpty() && isSignUpPasswordValid(password))
        val json = post("sign-up", JSONObject().put("email", normalizedEmail).put("password", password))
        check(json.opt("status") == "verification_email_sent")
        return (json.opt("signup_token") as? String)?.takeIf { it.isNotBlank() }
            ?: error("인증 요청 응답이 올바르지 않습니다.")
    }

    suspend fun verify(signupToken: String, code: String) {
        require(signupToken.isNotBlank() && code.matches(Regex("[0-9]{6}")))
        val json = post("verify-email", JSONObject()
            .put("signup_token", signupToken).put("verification_code", code))
        check(json.opt("status") == "email_verified") { "인증 완료 응답이 올바르지 않습니다." }
    }

    private suspend fun post(path: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl()}/auth/native/$path")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = calls.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (it.code != 200) {
                                throw EmailSignUpException(
                                    signupErrorMessage(it.code, path, it.body.string()),
                                )
                            }
                            JSONObject(it.body.string())
                        }
                    }
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
}

private fun signupErrorMessage(status: Int, path: String, body: String): String {
    val code = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("code")
    }.getOrNull()
    return when (code) {
        "email_already_registered" -> "이미 가입된 이메일입니다. 로그인해 주세요."
        "invalid_verification_code" -> "인증 코드가 올바르지 않거나 만료됐습니다."
        "invalid_signup_token" -> "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요."
        "email_delivery_unavailable", "email_delivery_failed", "email_auth_unavailable" ->
            "인증 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요."
        else -> when (status) {
            400 -> if (path == "verify-email") {
                "인증 코드가 올바르지 않거나 만료됐습니다."
            } else {
                "이메일과 비밀번호를 확인해 주세요."
            }
            409 -> "이미 가입된 이메일입니다. 로그인해 주세요."
            429 -> "요청이 많습니다. 잠시 후 다시 시도해 주세요."
            502, 503 -> "인증 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요."
            else -> "요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
    }
}
