package com.framework.innolive.feature.live.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.framework.innolive.feature.live.AnonymizationState
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.CreatedSession
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android의 org.json은 JVM 단위 테스트의 org.json과 달리 파싱 오류 메시지에 입력 전체를 붙입니다.
 * 잘린 응답의 예외가 스트림 키와 owner token을 싣지 않는지 실제 Android 런타임에서 확인합니다.
 */
@RunWith(AndroidJUnit4::class)
class DirectPublishParsingDeviceTest {
    private val secret = "leak-check-secret-0123456789"
    private val session = CreatedSession("session-1", "owner-1", AnonymizationState.DISABLED, BroadcastProvider.YOUTUBE)

    private fun api(body: String) = DirectPublishApi(
        serverUrl = "https://api.example.test",
        accessToken = "access",
        refreshAccessToken = { "access" },
        client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build(),
        log = {},
    )

    private fun assertNoSecret(error: Throwable?) {
        assertNotNull("잘린 응답이 예외 없이 처리됐습니다.", error)
        var current = error
        while (current != null) {
            assertFalse("${current.javaClass.simpleName} 메시지에 키가 있습니다.", current.message.orEmpty().contains(secret))
            assertFalse(current.toString().contains(secret))
            current.suppressed.forEach { assertFalse(it.toString().contains(secret)) }
            current = current.cause
        }
    }

    @Test fun truncatedResponsesDoNotEchoStreamKeyOrOwnerToken() {
        assertNoSecret(runCatching {
            api("""{"session_id":"s","owner_token":"$secret","ai_processing":"on_device"""")
                .createSession(BroadcastProvider.YOUTUBE, DirectPublishResolution.HD_720)
        }.exceptionOrNull())
        assertNoSecret(runCatching {
            api("""{"stream":{"broadcast_phase":"prepared"},"ingest_url":"rtmps://a.rtmp.youtube.com/live2/$secret""")
                .prepare(session, BroadcastProvider.YOUTUBE)
        }.exceptionOrNull())
        assertNoSecret(runCatching {
            api("""{"broadcast_phase":"live","direct_ingest":[{"provider":"chzzk","ingest_url":"rtmp://h/relay/$secret"""")
                .goLive(session, BroadcastProvider.CHZZK)
        }.exceptionOrNull())
        assertNoSecret(runCatching {
            parseDirectPrepare("""{"ingest_url":"rtmps://h/live2/$secret""", BroadcastProvider.YOUTUBE)
        }.exceptionOrNull())
        assertNoSecret(runCatching {
            parseDirectGoLive("""{"direct_ingest":[{"ingest_url":"rtmp://h/relay/$secret""", BroadcastProvider.CHZZK)
        }.exceptionOrNull())
    }
}
