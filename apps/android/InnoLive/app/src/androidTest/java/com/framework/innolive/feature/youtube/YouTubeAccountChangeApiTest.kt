package com.framework.innolive.feature.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeAccountChangeApiTest {
    @Test
    fun successfulReplacementUsesConnectResponseWithoutSecondAccountRequest() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.url.encodedPath}"
            val payload = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            assertEquals("new-code", JSONObject(payload).getString("server_auth_code"))
            assertEquals("native", JSONObject(payload).getString("code_source"))
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"connected":true,"provider":"youtube","channel":{"id":"new-channel","title":"새 채널"}}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()
        val api = YouTubeApi("https://example.test")
        YouTubeApi::class.java.getDeclaredField("httpClient").apply {
            isAccessible = true
            set(api, client)
        }

        try {
            assertEquals(
                StreamingAccount("youtube", "new-channel", "새 채널", reconnectRequired = false),
                api.connect("new-code", "test-access-token"),
            )
            assertEquals(listOf("POST /auth/youtube/connect"), requests)
        } finally {
            api.close()
        }
    }
}
