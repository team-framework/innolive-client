package com.framework.innolive.feature.login

import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.googleAuthEndpoint
import com.framework.innolive.ui.text.UiText
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

internal class EmailSignUpException(error: UiText) : EmailAuthenticationException(error)

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

private fun signupErrorMessage(status: Int, path: String, body: String): UiText {
    val code = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("code")
    }.getOrNull()
    return when (code) {
        "email_already_registered" -> UiText.Resource(R.string.error_email_already_registered)
        "invalid_verification_code" -> UiText.Resource(R.string.error_email_verification_code)
        "invalid_signup_token" -> UiText.Resource(R.string.error_email_signup_expired)
        "email_delivery_unavailable", "email_delivery_failed", "email_auth_unavailable" ->
            UiText.Resource(R.string.error_email_delivery)
        else -> when (status) {
            400 -> if (path == "verify-email") {
                UiText.Resource(R.string.error_email_verification_code)
            } else {
                UiText.Resource(R.string.error_email_signup_credentials)
            }
            409 -> UiText.Resource(R.string.error_email_already_registered)
            429 -> UiText.Resource(R.string.error_too_many_requests)
            502, 503 -> UiText.Resource(R.string.error_email_delivery)
            else -> UiText.Resource(R.string.error_request_failed)
        }
    }
}
