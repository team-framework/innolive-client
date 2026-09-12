package com.framework.innolive.feature.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val jsonMediaType = "application/json".toMediaType()

class YouTubeApi(serverUrl: String) : AutoCloseable {
    private val serverBaseUrl = serverUrl.trim().trimEnd('/').toHttpUrl().also { url ->
        require(url.isHttps) { "INNOLIVE_SERVER_URL must use HTTPS." }
    }
    private val httpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun configuration(): YouTubeConfiguration = withContext(Dispatchers.IO) {
        execute(requestBuilder("/auth/youtube/config").get().build(), "YouTube configuration") { body ->
            JSONObject(body).let { response ->
                YouTubeConfiguration(
                    webClientId = response.requiredString("web_client_id"),
                    scope = response.requiredString("scope"),
                )
            }
        }
    }

    suspend fun connect(serverAuthCode: String, accessToken: String): Unit = withContext(Dispatchers.IO) {
        require(serverAuthCode.isNotBlank()) { "YouTube server authorization code must not be blank." }
        execute(
            requestBuilder("/auth/youtube/connect")
                .post(
                    JSONObject()
                        .put("server_auth_code", serverAuthCode)
                        .put("code_source", "native")
                        .toString()
                        .toRequestBody(jsonMediaType),
                )
                .bearer(accessToken)
                .build(),
            "YouTube account connection",
        ) { body ->
            val response = JSONObject(body)
            check(response.optBoolean("connected") && response.optString("provider") == "youtube")
        }
    }

    suspend fun listAccounts(accessToken: String): List<StreamingAccount> = withContext(Dispatchers.IO) {
        execute(
            requestBuilder("/auth/streaming/accounts").get().bearer(accessToken).build(),
            "streaming account list",
        ) { body ->
            val accounts = JSONArray(body)
            List(accounts.length()) { index ->
                accounts.getJSONObject(index).let { account ->
                    StreamingAccount(
                        provider = account.requiredString("provider"),
                        channelId = account.requiredString("channel_id"),
                        channelTitle = account.optString("channel_title").trim(),
                        reconnectRequired = account.getBoolean("reconnect_required"),
                    )
                }
            }
        }
    }

    override fun close() {
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun requestBuilder(path: String): Request.Builder =
        Request.Builder().url(checkNotNull(serverBaseUrl.resolve(path))).header("Accept", "application/json")

    private fun Request.Builder.bearer(accessToken: String): Request.Builder {
        require(accessToken.isNotBlank()) { "Access token must not be blank." }
        return header("Authorization", "Bearer $accessToken")
    }

    private fun <T> execute(request: Request, operation: String, parse: (String) -> T): T = try {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw YouTubeApiException(response.code, operation)
            parse(body)
        }
    } catch (exception: YouTubeApiException) {
        throw exception
    } catch (exception: IOException) {
        throw YouTubeApiException(null, operation, exception)
    }

    private fun JSONObject.requiredString(name: String): String = optString(name).trim()
        .takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("YouTube response is missing $name.")
}
