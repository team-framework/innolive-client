package com.framework.innolive.feature.login

import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import com.framework.innolive.feature.login.oauth.google.googleAuthEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class EmailSignInException(message: String) : IOException(message)

internal class EmailSignInApi(
    private val calls: Call.Factory = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val endpoint: () -> String = {
        googleAuthEndpoint().toString().removeSuffix("/auth/google") + "/auth/sign-in"
    },
) {
    suspend fun authenticate(email: String, password: String): GoogleSessionStore.Session =
        withContext(Dispatchers.IO) {
            val normalizedEmail = email.trim()
            require(normalizedEmail.isNotEmpty() && password.isNotEmpty())
            val request = Request.Builder()
                .url(endpoint())
                .post(JSONObject().put("email", normalizedEmail).put("password", password)
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val call = calls.newCall(request)
            val session = suspendCancellableCoroutine<GoogleSessionStore.Session> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                if (it.code != 200) {
                                    throw EmailSignInException(when (it.code) {
                                        401 -> "이메일 또는 비밀번호를 확인해 주세요."
                                        429 -> "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요."
                                        503 -> "지금은 이메일로 로그인할 수 없습니다. 잠시 후 다시 시도해 주세요."
                                        else -> "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요."
                                    })
                                }
                                parseEmailSession(it.body.string(), normalizedEmail)
                            }
                        }
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                })
            }
            currentCoroutineContext().ensureActive()
            session
        }
}

internal fun parseEmailSession(body: String, email: String): GoogleSessionStore.Session {
    val json = JSONObject(body)
    fun token(key: String): String = (json.opt(key) as? String)
        ?.takeIf { it.isNotBlank() } ?: error("인증 응답의 토큰이 올바르지 않습니다.")
    fun duration(key: String): Long = json.opt(key)?.toString()?.toLongOrNull()
        ?.takeIf { it > 0 } ?: error("인증 응답의 만료 시간이 올바르지 않습니다.")
    val type = token("token_type")
    check(type.equals("Bearer", ignoreCase = true)) { "지원하지 않는 인증 방식입니다." }
    return GoogleSessionStore.Session(
        accessToken = token("access_token"), refreshToken = token("refresh_token"),
        tokenType = type, expiresIn = duration("expires_in"),
        refreshExpiresIn = duration("refresh_expires_in"), profileEmail = email,
    )
}

internal suspend fun authenticateAndSaveEmailSession(
    email: String,
    password: String,
    authenticate: suspend (String, String) -> GoogleSessionStore.Session,
    save: (GoogleSessionStore.Session) -> Unit,
) {
    val session = authenticate(email, password)
    // 화면 이탈이나 재생성으로 취소된 요청은 세션을 저장하지 않는다.
    currentCoroutineContext().ensureActive()
    save(session)
}
