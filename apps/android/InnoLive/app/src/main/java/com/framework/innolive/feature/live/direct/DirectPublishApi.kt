package com.framework.innolive.feature.live.direct

import android.util.Log
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.ServerApiException
import com.framework.innolive.feature.live.buildBroadcastSettingsPayload
import com.framework.innolive.feature.live.buildChzzkSettingsPayload
import com.framework.innolive.feature.live.parseBroadcastState
import com.framework.innolive.feature.live.parseCreatedSession
import com.framework.innolive.feature.live.parseServerApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 온디바이스 직접 송출 세션의 서버 API입니다(contracts/api/direct-publish-v1.md).
 *
 * 서버는 플랫폼 방송 생성·라이브 전환·종료만 맡고 영상은 앱이 플랫폼으로 직접 보냅니다.
 * 모든 함수는 네트워크를 기다리므로 메인 스레드가 아닌 곳에서 호출합니다. 응답 본문에
 * 스트림 키가 들어 있으므로 본문과 URL을 로그에 남기지 않습니다.
 */
internal class DirectPublishApi(
    serverUrl: String,
    accessToken: String,
    private val refreshAccessToken: suspend () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val log: (String) -> Unit = { message -> Log.i(LOG_TAG, message) },
) : AutoCloseable {
    private val baseUrl: HttpUrl = serverUrl.trim().trimEnd('/').toHttpUrl().also { url ->
        require(url.isHttps) { "서버 주소는 HTTPS여야 합니다." }
    }

    @Volatile
    private var accessToken: String = accessToken.trim().also { require(it.isNotEmpty()) }

    /** 409 `session_already_exists`는 [ServerApiException]으로 전달합니다. 기존 세션 정리는 호출자가 맡습니다. */
    fun createSession(provider: BroadcastProvider, resolution: DirectPublishResolution): CreatedSession {
        val body = buildDirectSessionRequest(provider, resolution)
        val payload = execute("create_session", request("/sessions").post(body.toJsonBody()))
        val created = parseCreatedSession(payload)
        // 최상위 ai_processing을 모르는 서버는 server 세션을 만듭니다. 서버 송출 세션으로 직접 송출하지 않습니다.
        check(JSONObject(payload).optString("ai_processing") == ON_DEVICE_PROCESSING) {
            "서버가 온디바이스 세션을 만들지 않았습니다."
        }
        check(created.provider == provider) { "서버가 다른 방송 플랫폼의 세션을 생성했습니다." }
        return created
    }

    fun saveYouTubeSettings(session: CreatedSession, settings: BroadcastSettings) {
        requireNotNull(settings.madeForKids) { "made_for_kids가 필요합니다." }
        execute("broadcast_settings", sessionRequest(session, "broadcast")
            .put(buildBroadcastSettingsPayload(settings).toJsonBody()))
    }

    fun saveChzzkSettings(session: CreatedSession, settings: ChzzkBroadcastSettings) {
        val payload = execute("broadcast_settings", sessionRequest(session, "broadcast", BroadcastProvider.CHZZK)
            .put(buildChzzkSettingsPayload(settings).toJsonBody()))
        val confirmed = checkNotNull(JSONObject(payload).optJSONObject("chzzk_broadcast")) {
            "서버가 치지직 설정 저장을 확인하지 않았습니다."
        }
        check(confirmed.optString("title") == settings.title.trim() &&
            confirmed.optString("category_type") == settings.categoryType &&
            confirmed.optString("category_id") == settings.categoryId) {
            "서버가 치지직 설정 저장을 확인하지 않았습니다."
        }
    }

    /** [allowConcurrent]는 채널이 이미 다른 도구로 라이브 중임을 사용자가 확인한 YouTube 재요청에만 씁니다. */
    fun prepare(
        session: CreatedSession,
        provider: BroadcastProvider,
        allowConcurrent: Boolean = false,
    ): DirectPrepareResult {
        val body = JSONObject().put("provider", provider.wireValue)
        if (allowConcurrent && provider == BroadcastProvider.YOUTUBE) body.put("allow_concurrent", true)
        val payload = execute("stream_prepare", sessionRequest(session, "stream/prepare").post(body.toJsonBody()))
        return parseDirectPrepare(payload, provider)
    }

    /**
     * 준비된 모든 대상을 한 번에 공개 전환합니다. `409 broadcast_not_ready`는
     * [ServerApiException]으로 전달하며 재시도는 호출자가 송출을 유지한 채 맡습니다.
     */
    fun goLive(session: CreatedSession, provider: BroadcastProvider): DirectGoLiveResult {
        val payload = execute("stream_golive", sessionRequest(session, "stream/golive").post(JSONObject().toJsonBody()))
        return parseDirectGoLive(payload, provider)
    }

    /** 서버 방송만 끝냅니다. 요청 성공 여부와 무관하게 호출자가 RTMP 연결을 끊습니다. */
    fun stop(session: CreatedSession, provider: BroadcastProvider): BroadcastState? {
        val payload = execute("stream_stop", sessionRequest(session, "stream/stop", provider).post(JSONObject().toJsonBody()))
        return parseBroadcastState(payload, provider)
    }

    /** 이미 없는 세션(404)과 소유권이 없는 세션(403)도 정리된 것으로 봅니다. */
    fun deleteSession(session: CreatedSession) {
        val response = executeAuthenticated("delete_session", sessionRequest(session, null).delete().build())
        response.use {
            if (it.code !in setOf(204, 403, 404)) throw parseServerApiException(it.body.string(), it.code)
        }
    }

    /** 예외를 던지지 않고 결과로 앱 동작을 구분합니다. 취소만 그대로 전달합니다. */
    fun heartbeat(session: CreatedSession, heartbeat: DirectHeartbeat): HeartbeatResult {
        val request = sessionRequest(session, "heartbeat").post(buildHeartbeatPayload(heartbeat).toJsonBody()).build()
        return try {
            executeAuthenticated("heartbeat", request).use { response ->
                val payload = response.body.string()
                if (response.isSuccessful) return@use parseHeartbeatAccepted(payload)
                val error = parseServerApiException(payload, response.code)
                when {
                    response.code == 404 -> HeartbeatResult.StopAll(HeartbeatStopReason.SESSION_GONE)
                    response.code == 403 -> HeartbeatResult.StopAll(HeartbeatStopReason.OWNERSHIP_LOST)
                    response.code == 409 && error.code == "heartbeat_not_applicable" ->
                        HeartbeatResult.StopAll(HeartbeatStopReason.NOT_APPLICABLE)
                    response.code == 400 -> HeartbeatResult.Invalid(error.field)
                    response.code == 401 -> HeartbeatResult.Unauthorized
                    else -> HeartbeatResult.RetryLater
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (_: ServerApiException) {
            HeartbeatResult.Unauthorized
        } catch (_: Exception) {
            HeartbeatResult.RetryLater
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
    }

    private fun request(path: String): Request.Builder =
        Request.Builder().url(checkNotNull(baseUrl.resolve(path)))

    private fun sessionRequest(
        session: CreatedSession,
        path: String?,
        provider: BroadcastProvider? = null,
    ): Request.Builder {
        val url = baseUrl.newBuilder().addPathSegment("sessions").addPathSegment(session.sessionId).apply {
            path?.split('/')?.forEach(::addPathSegment)
            provider?.let { addQueryParameter("provider", it.wireValue) }
        }.build()
        return Request.Builder().url(url).header(OWNER_TOKEN_HEADER, session.ownerToken)
    }

    private fun execute(operation: String, builder: Request.Builder): String =
        executeAuthenticated(operation, builder.build()).use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) throw parseServerApiException(payload, response.code)
            payload
        }

    /** 401이면 로그인 토큰을 한 번 갱신하고 다시 보냅니다. 갱신 실패는 `unauthorized`입니다. */
    private fun executeAuthenticated(operation: String, request: Request): Response {
        val response = send(operation, request)
        if (response.code != 401) return response
        response.close()
        try {
            accessToken = runBlocking { refreshAccessToken() }.trim().also { require(it.isNotEmpty()) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw ServerApiException("unauthorized", statusCode = 401, cause = exception)
        }
        return send(operation, request)
    }

    private fun send(operation: String, request: Request): Response {
        val authorized = request.newBuilder().header("Authorization", "Bearer $accessToken").build()
        return client.newCall(authorized).execute().also { response ->
            log("operation=$operation status=${response.code}")
        }
    }

    private fun JSONObject.toJsonBody() = toString().toRequestBody(JSON_MEDIA_TYPE)

    private companion object {
        const val LOG_TAG = "DirectPublish"
        const val OWNER_TOKEN_HEADER = "X-Session-Owner-Token"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
