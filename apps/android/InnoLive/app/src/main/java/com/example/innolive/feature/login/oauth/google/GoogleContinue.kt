package com.example.innolive.feature.login.oauth.google

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.innolive.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

private const val NETWORK_TIMEOUT_MILLIS = 10_000

suspend fun continueWithGoogle(context: Context) {
    val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
    require(googleWebClientId.isNotEmpty()) { "GOOGLE_WEB_CLIENT_ID must not be blank." }

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(
            GetSignInWithGoogleOption.Builder(googleWebClientId).build(),
        )
        .build()
    val credential = CredentialManager.create(context)
        .getCredential(context, request)
        .credential
    val googleCredential = credential as? CustomCredential
        ?: throw IllegalStateException("Google ID token credential was not returned.")
    check(googleCredential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "Unexpected credential type returned for Google sign-in."
    }
    val googleIdToken = GoogleIdTokenCredential.createFrom(googleCredential.data).idToken

    require(googleIdToken.isNotBlank()) { "Google ID token must not be blank." }

    withContext(Dispatchers.IO) {
        val session = exchangeGoogleIdToken(googleAuthEndpoint(), googleIdToken)
        GoogleSessionStore(context).save(session)
    }
}

private fun googleAuthEndpoint(): URL {
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

    return URL("$baseUrl/auth/google")
}

private fun exchangeGoogleIdToken(endpoint: URL, googleIdToken: String): GoogleSessionStore.Session {
    val connection = endpoint.openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
        connection.readTimeout = NETWORK_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(JSONObject().put("id_token", googleIdToken).toString())
        }

        if (connection.responseCode !in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            connection.errorStream?.close()
            throw IOException("Google authentication request failed with HTTP ${connection.responseCode}.")
        }

        val responseBody = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        return parseGoogleSession(responseBody)
    } finally {
        connection.disconnect()
    }
}

internal fun parseGoogleSession(responseBody: String): GoogleSessionStore.Session {
    val response = try {
        JSONObject(responseBody)
    } catch (exception: Exception) {
        throw IllegalStateException("Google authentication response is invalid.", exception)
    }

    return GoogleSessionStore.Session(
        accessToken = response.requiredString("access_token"),
        refreshToken = response.requiredString("refresh_token"),
        tokenType = response.requiredString("token_type"),
        expiresIn = response.requiredPositiveLong("expires_in"),
        refreshExpiresIn = response.requiredPositiveLong("refresh_expires_in"),
    )
}

private fun JSONObject.requiredString(name: String): String =
    optString(name).takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Google authentication response is missing $name.")

private fun JSONObject.requiredPositiveLong(name: String): Long =
    opt(name)?.toString()?.toLongOrNull()?.takeIf { it > 0 }
        ?: throw IllegalStateException("Google authentication response has an invalid $name.")
