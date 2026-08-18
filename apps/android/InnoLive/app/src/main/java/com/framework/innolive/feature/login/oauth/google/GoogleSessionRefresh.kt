package com.framework.innolive.feature.login.oauth.google

import android.content.Context
import com.framework.innolive.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

private const val REFRESH_TIMEOUT_MILLIS = 10_000

suspend fun refreshGoogleSession(
    context: Context,
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

        if (connection.responseCode !in HttpURLConnection.HTTP_OK until
            HttpURLConnection.HTTP_MULT_CHOICE
        ) {
            connection.errorStream?.close()
            throw IOException("Authentication refresh failed with HTTP ${connection.responseCode}.")
        }

        val responseBody = connection.inputStream.bufferedReader().use { reader ->
            reader.readText()
        }
        mergeRefreshedSession(responseBody, currentSession).also { refreshedSession ->
            GoogleSessionStore(context).save(refreshedSession)
        }
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
