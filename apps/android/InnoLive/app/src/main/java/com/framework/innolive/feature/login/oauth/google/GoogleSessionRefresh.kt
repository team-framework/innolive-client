package com.framework.innolive.feature.login.oauth.google

import com.framework.innolive.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

private const val REFRESH_TIMEOUT_MILLIS = 10_000

/** 서버가 refresh token을 거부했습니다. 기존 세션 만료 동작(로그인 복귀)을 따릅니다. */
class InvalidRefreshTokenException(val statusCode: Int) :
    IOException("Authentication refresh rejected with HTTP $statusCode.")

/**
 * 400·401·403은 refresh token 자체의 거부다. 그 밖의 상태와 네트워크 오류는 일시 실패로 보고
 * 로컬 세션과 재시도 가능 상태를 유지한다(account-deletion-v1.md).
 */
internal fun isInvalidRefreshTokenStatus(statusCode: Int): Boolean = statusCode in setOf(400, 401, 403)

class GoogleAuthenticationApi : AuthenticationApi {
    override suspend fun refresh(
        currentSession: GoogleSessionStore.Session,
    ): GoogleSessionStore.Session = performGoogleSessionRefresh(currentSession)

    suspend fun authenticate(
        googleIdToken: String,
        profileName: String,
        profileEmail: String,
    ): GoogleSessionStore.Session = withContext(Dispatchers.IO) {
        exchangeGoogleIdToken(googleAuthEndpoint(), googleIdToken).copy(
            profileName = profileName,
            profileEmail = profileEmail,
            signInMethod = SignInMethod.GOOGLE,
        )
    }
}

internal suspend fun performGoogleSessionRefresh(
    currentSession: GoogleSessionStore.Session,
): GoogleSessionStore.Session = withContext(Dispatchers.IO) {
    val connection = refreshEndpoint().openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = REFRESH_TIMEOUT_MILLIS
        connection.readTimeout = REFRESH_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(
                JSONObject()
                    .put("refresh_token", currentSession.refreshToken)
                    .toString(),
            )
        }

        val statusCode = connection.responseCode
        if (statusCode !in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            connection.errorStream?.close()
            if (isInvalidRefreshTokenStatus(statusCode)) throw InvalidRefreshTokenException(statusCode)
            throw IOException("Authentication refresh failed with HTTP $statusCode.")
        }

        val responseBody = connection.inputStream.bufferedReader().use { reader ->
            reader.readText()
        }
        mergeRefreshedSession(responseBody, currentSession)
    } finally {
        connection.disconnect()
    }
}

internal fun mergeRefreshedSession(
    responseBody: String,
    currentSession: GoogleSessionStore.Session,
): GoogleSessionStore.Session = parseGoogleSession(responseBody).copy(
    profileName = currentSession.profileName,
    profileEmail = currentSession.profileEmail,
    signInMethod = currentSession.signInMethod,
)

private fun refreshEndpoint(): URL {
    val baseUrl = BuildConfig.INNOLIVE_SERVER_URL.trim().trimEnd('/')
    require(baseUrl.isNotEmpty()) { "INNOLIVE_SERVER_URL must not be blank." }

    val serverUrl = try {
        URL(baseUrl)
    } catch (exception: MalformedURLException) {
        throw IllegalArgumentException("INNOLIVE_SERVER_URL is invalid.", exception)
    }
    require(serverUrl.protocol.equals("https", ignoreCase = true)) {
        "INNOLIVE_SERVER_URL must use HTTPS."
    }
    require(serverUrl.host.isNotBlank()) { "INNOLIVE_SERVER_URL must include a host." }

    return URL("$baseUrl/auth/refresh")
}
