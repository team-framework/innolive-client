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

/** 직접 송출 상태 머신이 쓰는 서버 동작입니다. 테스트는 가짜 서버를 넣습니다. */
internal interface DirectSessionServer : AutoCloseable {
    fun createSession(provider: BroadcastProvider, resolution: DirectPublishResolution): CreatedSession
    fun saveYouTubeSettings(session: CreatedSession, settings: BroadcastSettings)
    fun saveChzzkSettings(session: CreatedSession, settings: ChzzkBroadcastSettings)
    fun chzzkDefaults(session: CreatedSession): ChzzkBroadcastSettings
    fun prepare(session: CreatedSession, provider: BroadcastProvider, allowConcurrent: Boolean = false): DirectPrepareResult
    fun goLive(session: CreatedSession, provider: BroadcastProvider): DirectGoLiveResult
    fun stop(session: CreatedSession, provider: BroadcastProvider): BroadcastState?
    fun deleteSession(session: CreatedSession)
    fun fetchSession(session: CreatedSession): String
}

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
) : DirectSessionServer {
    private val baseUrl: HttpUrl = serverUrl.trim().trimEnd('/').toHttpUrl().also { url ->
        require(url.isHttps) { "서버 주소는 HTTPS여야 합니다." }
    }

    @Volatile
    private var accessToken: String = accessToken.trim().also { require(it.isNotEmpty()) }

    /**
     * 409 `session_already_exists`는 [ServerApiException]으로 전달합니다. 기존 세션 정리는 호출자가 맡습니다.
     * 만들어진 세션을 쓸 수 없으면 owner token이 이 응답에만 있으므로 바로 삭제하고
     * [DirectSessionRejectedException]을 던집니다.
     */
    override fun createSession(provider: BroadcastProvider, resolution: DirectPublishResolution): CreatedSession {
        val body = buildDirectSessionRequest(provider, resolution)
        val payload = execute("create_session", sessionsRequest().post(body.toJsonBody()))
        // 응답에 owner token이 있어 파싱 오류가 원문을 싣지 않게 합니다.
        val (created, processing) = parseResponseSafely {
            parseCreatedSession(payload) to JSONObject(payload).optString("ai_processing")
        }
        val rejection = when {
            // 최상위 ai_processing을 모르는 서버는 server 세션을 만듭니다. 서버 송출 세션으로 직접 송출하지 않습니다.
            processing != ON_DEVICE_PROCESSING -> "서버가 온디바이스 세션을 만들지 않았습니다."
            created.provider != provider -> "서버가 다른 방송 플랫폼의 세션을 생성했습니다."
            else -> return created
        }
        // owner token은 이 응답에만 있습니다. 삭제가 취소돼도 호출자가 보관할 수 있게 세션을 넘깁니다.
        try {
            deleteSession(created)
        } catch (exception: CancellationException) {
            throw DirectSessionRejectedException(rejection, created, cleanupCancelled = true)
                .apply { addSuppressed(exception) }
        } catch (_: Exception) {
            throw DirectSessionRejectedException(rejection, created)
        }
        throw DirectSessionRejectedException(rejection, null)
    }

    override fun saveYouTubeSettings(session: CreatedSession, settings: BroadcastSettings) {
        requireNotNull(settings.madeForKids) { "made_for_kids가 필요합니다." }
        // 생략하면 서버는 세션 기본 플랫폼으로 해석합니다. 치지직 세션에 YouTube 대상을 더할 때도 YouTube 설정으로 읽게 합니다.
        execute("broadcast_settings", sessionRequest(session, "broadcast", BroadcastProvider.YOUTUBE)
            .put(buildBroadcastSettingsPayload(settings).toJsonBody()))
    }

    override fun saveChzzkSettings(session: CreatedSession, settings: ChzzkBroadcastSettings) {
        val payload = execute("broadcast_settings", sessionRequest(session, "broadcast", BroadcastProvider.CHZZK)
            .put(buildChzzkSettingsPayload(settings).toJsonBody()))
        val confirmed = checkNotNull(parseResponseSafely { JSONObject(payload).optJSONObject("chzzk_broadcast") }) {
            "서버가 치지직 설정 저장을 확인하지 않았습니다."
        }
        check(confirmed.optString("title") == settings.title.trim() &&
            confirmed.optString("category_type") == settings.categoryType &&
            confirmed.optString("category_id") == settings.categoryId) {
            "서버가 치지직 설정 저장을 확인하지 않았습니다."
        }
    }

    /** [allowConcurrent]는 채널이 이미 다른 도구로 라이브 중임을 사용자가 확인한 YouTube 재요청에만 씁니다. */
    override fun prepare(
        session: CreatedSession,
        provider: BroadcastProvider,
        allowConcurrent: Boolean,
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
    override fun goLive(session: CreatedSession, provider: BroadcastProvider): DirectGoLiveResult {
        val payload = execute("stream_golive", sessionRequest(session, "stream/golive").post(JSONObject().toJsonBody()))
        return parseDirectGoLive(payload, provider)
    }

    /** 서버 방송만 끝냅니다. 요청 성공 여부와 무관하게 호출자가 RTMP 연결을 끊습니다. */
    override fun stop(session: CreatedSession, provider: BroadcastProvider): BroadcastState? {
        val payload = execute("stream_stop", sessionRequest(session, "stream/stop", provider).post(JSONObject().toJsonBody()))
        return parseResponseSafely { parseBroadcastState(payload, provider) }
    }

    /** 이미 없는 세션(404)과 소유권이 없는 세션(403)도 정리된 것으로 봅니다. */
    override fun deleteSession(session: CreatedSession) {
        val response = executeAuthenticated("delete_session", sessionRequest(session, null).delete().build())
        response.use {
            if (it.code !in setOf(204, 403, 404)) throw parseServerApiException(it.body.string(), it.code)
        }
    }

    /** 치지직 설정을 비워 준비할 때 쓰는 서버 기본값입니다. */
    override fun chzzkDefaults(session: CreatedSession): ChzzkBroadcastSettings {
        val payload = execute("broadcast_defaults",
            sessionRequest(session, "broadcast/defaults", BroadcastProvider.CHZZK).get())
        return parseResponseSafely {
            val value = JSONObject(payload)
            val tags = value.optJSONArray("tags")
            ChzzkBroadcastSettings(value.optString("title"), value.optString("category_type"),
                value.optString("category_id"),
                if (tags == null) emptyList() else List(tags.length()) { tags.getString(it) })
        }
    }

    /** 세션 상태 조회입니다. 원문은 상태 파싱에만 쓰고 로그에 남기지 않습니다. */
    override fun fetchSession(session: CreatedSession): String =
        execute("session_status", sessionRequest(session, null).get())

    /** 예외를 던지지 않고 결과로 앱 동작을 구분합니다. 취소만 그대로 전달합니다. */
    fun heartbeat(session: CreatedSession, heartbeat: DirectHeartbeat): HeartbeatResult {
        val request = try {
            sessionRequest(session, "heartbeat").post(buildHeartbeatPayload(heartbeat).toJsonBody()).build()
        } catch (exception: InvalidHeartbeatFieldException) {
            return HeartbeatResult.Invalid(exception.field)
        } catch (_: Exception) {
            return HeartbeatResult.Invalid(null)
        }
        return try {
            executeAuthenticated("heartbeat", request).use { response ->
                // 상태 코드만으로 종료가 확정됩니다. 본문 전송이 끊겨도 재시도로 바꾸지 않습니다.
                when (response.code) {
                    404 -> return@use HeartbeatResult.StopAll(HeartbeatStopReason.SESSION_GONE)
                    403 -> return@use HeartbeatResult.StopAll(HeartbeatStopReason.OWNERSHIP_LOST)
                }
                val payload = response.body.string()
                if (response.isSuccessful) return@use parseHeartbeatAccepted(payload)
                val error = parseServerApiException(payload, response.code)
                when {
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

    /** 서버 주소에 경로가 있어도 모든 요청이 같은 기준 경로 아래 `sessions`를 씁니다. */
    private fun sessionsUrl(): HttpUrl.Builder = baseUrl.newBuilder().addPathSegment("sessions")

    private fun sessionsRequest(): Request.Builder = Request.Builder().url(sessionsUrl().build())

    private fun sessionRequest(
        session: CreatedSession,
        path: String?,
        provider: BroadcastProvider? = null,
    ): Request.Builder {
        val url = sessionsUrl().addPathSegment(session.sessionId).apply {
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

/**
 * 서버가 세션을 만들었지만 직접 송출에 쓸 수 없어 거부했습니다. 삭제에 실패하거나 취소되면
 * [uncleanedSession]으로 owner token을 넘기므로 호출자가 정리 저장소에 보관해 다음에 지웁니다.
 * [cleanupCancelled]이면 토큰 갱신 취소(계정 변경 등)로 삭제를 끝내지 못한 것이며, 원래 취소는
 * suppressed 예외로 붙어 있습니다.
 */
internal class DirectSessionRejectedException(
    message: String,
    val uncleanedSession: CreatedSession?,
    val cleanupCancelled: Boolean = false,
) : IllegalStateException(message)
