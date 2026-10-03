package com.framework.innolive.feature.live

import com.framework.innolive.feature.youtube.StreamingAccount
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
import java.security.SecureRandom
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class ChzzkOAuthConfig(val authorizeUrl: String, val redirectUri: String)
internal data class ChzzkCategory(val type: String, val id: String, val value: String)

internal class ChzzkApi(serverUrl: String) : AutoCloseable {
    private val base = serverUrl.trim().trimEnd('/').toHttpUrl().also { require(it.isHttps) }
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    suspend fun config(state: String): ChzzkOAuthConfig = withContext(Dispatchers.IO) {
        val url = checkNotNull(base.resolve("/auth/chzzk/config"))
            .newBuilder().addQueryParameter("state", state).build()
        val data = request(Request.Builder().url(url).get().build())
        val authorize = data.optString("authorize_url")
        val redirect = data.optString("redirect_uri")
        require(authorize.startsWith("https://") && redirect.startsWith("https://")) {
            "서버의 치지직 OAuth 설정이 비어 있습니다."
        }
        val authorizationUrl = authorize.toHttpUrl()
        require(authorizationUrl.queryParameter("state") == state &&
            authorizationUrl.queryParameter("redirectUri") == redirect) {
            "서버의 치지직 OAuth 주소가 요청과 다릅니다."
        }
        ChzzkOAuthConfig(authorize, redirect)
    }

    suspend fun accounts(token: String): List<StreamingAccount> = withContext(Dispatchers.IO) {
        val data = request(builder("/auth/streaming/accounts").get().bearer(token).build(), array = true)
        val list = data.getJSONArray("items")
        List(list.length()) { index ->
            val item = list.getJSONObject(index)
            StreamingAccount(item.getString("provider"), item.optString("channel_id"),
                item.optString("channel_title"), item.optBoolean("reconnect_required"))
        }
    }

    suspend fun categories(token: String, query: String): List<ChzzkCategory> = withContext(Dispatchers.IO) {
        val url = checkNotNull(base.resolve("/auth/chzzk/categories"))
            .newBuilder().addQueryParameter("query", query).addQueryParameter("size", "20").build()
        val data = request(Request.Builder().url(url).get().bearer(token).build())
        val items = data.getJSONArray("categories")
        List(items.length()) { index ->
            val item = items.getJSONObject(index)
            ChzzkCategory(item.getString("category_type"), item.getString("category_id"),
                item.getString("category_value"))
        }
    }

    suspend fun connect(token: String, code: String, state: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("code", code).put("state", state)
        val response = request(builder("/auth/chzzk/connect").post(payload.toString().toRequestBody(jsonType))
            .bearer(token).build())
        check(response.optBoolean("connected") && response.optString("provider") == "chzzk") {
            "서버가 치지직 연결을 확인하지 않았습니다."
        }
    }

    suspend fun disconnect(token: String) = withContext(Dispatchers.IO) {
        request(builder("/auth/streaming/accounts/chzzk").delete().bearer(token).build())
    }

    private fun builder(path: String) = Request.Builder().url(checkNotNull(base.resolve(path)))
    private fun Request.Builder.bearer(token: String): Request.Builder = header("Authorization", "Bearer $token")

    private fun request(req: Request, array: Boolean = false): JSONObject = client.newCall(req).execute().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) {
            val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
            throw ChzzkApiException(response.code, error?.optString("code").orEmpty(),
                error?.optJSONObject("details")?.optString("field").orEmpty())
        }
        if (array) JSONObject().put("items", JSONArray(text))
        else if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal class ChzzkApiException(val status: Int, val code: String, val field: String) : IOException(code)

internal fun newChzzkOAuthState(): String = ByteArray(32).also(SecureRandom()::nextBytes)
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

internal fun parseChzzkCallback(rawUrl: String, redirectUri: String, expectedState: String): String? {
    val actual = URI(rawUrl)
    val redirect = URI(redirectUri)
    if (actual.scheme != redirect.scheme || actual.host != redirect.host ||
        actual.port != redirect.port || actual.path != redirect.path || actual.rawFragment != null) return null
    val values = actual.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map { part ->
        val parts = part.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to
            URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
    }
    if (values.count { it.first == "state" } != 1 || values.count { it.first == "code" } > 1) {
        throw SecurityException("OAuth 콜백 형식이 올바르지 않습니다. 다시 연결하세요.")
    }
    if (values.firstOrNull { it.first == "state" }?.second != expectedState) {
        throw SecurityException("OAuth state 불일치. 다시 연결하세요.")
    }
    if (values.any { it.first == "error" }) throw IOException("치지직 계정 연결이 취소되거나 거절됐습니다.")
    return values.firstOrNull { it.first == "code" }?.second?.takeIf { it.isNotBlank() }
        ?: throw IOException("치지직 인가 코드가 없습니다. 다시 연결하세요.")
}
