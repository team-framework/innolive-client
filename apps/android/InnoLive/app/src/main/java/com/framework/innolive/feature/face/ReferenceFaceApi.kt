package com.framework.innolive.feature.face

import com.framework.innolive.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val REQUEST_TIMEOUT_SECONDS = 15L
private val jpegMediaType = "image/jpeg".toMediaType()

internal data class ReferenceFaceRegistrationResult(
    val registered: Boolean,
    val count: Int?,
)

internal data class ReferenceFace(
    val faceId: String,
    val registeredAt: String?,
)

internal data class ReferenceFaceStatus(
    val registered: Boolean,
    val source: String?,
    val registeredAt: String?,
    val count: Int?,
    val faces: List<ReferenceFace>,
)

internal class ReferenceFaceApiException(
    val statusCode: Int?,
    val code: String?,
    val detailsReason: String? = null,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class ReferenceFaceApi(
    serverUrl: String = BuildConfig.INNOLIVE_SERVER_URL,
    httpClient: OkHttpClient? = null,
) : AutoCloseable {
    private val ownsHttpClient = httpClient == null
    private val httpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val serverBaseUrl: HttpUrl = serverUrl.trim().trimEnd('/').toHttpUrlOrThrow()
    private val closeStarted = AtomicBoolean(false)

    suspend fun register(
        image: ByteArray,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ): ReferenceFaceRegistrationResult {
        require(image.isNotEmpty()) { "Reference face image must not be empty." }
        return parseRegistrationResponse(
            executeAuthenticated(
                accessToken = accessToken,
                refreshAccessToken = refreshAccessToken,
                buildRequest = { token -> buildRegisterRequest(image, token) },
            ),
        )
    }

    suspend fun getStatus(
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ): ReferenceFaceStatus = parseStatusResponse(
        executeAuthenticated(
            accessToken = accessToken,
            refreshAccessToken = refreshAccessToken,
            buildRequest = ::buildStatusRequest,
        ),
    )

    suspend fun deleteAll(
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ) {
        parseDeleteResponse(
            executeAuthenticated(
                accessToken = accessToken,
                refreshAccessToken = refreshAccessToken,
                buildRequest = { token -> buildDeleteRequest(token, faceId = null) },
            ),
        )
    }

    suspend fun deleteFace(
        faceId: String,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ) {
        require(faceId.isNotBlank()) { "Face id must not be blank." }
        parseDeleteResponse(
            executeAuthenticated(
                accessToken = accessToken,
                refreshAccessToken = refreshAccessToken,
                buildRequest = { token -> buildDeleteRequest(token, faceId) },
            ),
        )
    }

    override fun close() {
        if (!ownsHttpClient || !closeStarted.compareAndSet(false, true)) return

        val cleanup = Runnable {
            try {
                httpClient.dispatcher.cancelAll()
                httpClient.connectionPool.evictAll()
            } finally {
                httpClient.dispatcher.executorService.shutdown()
            }
        }
        try {
            httpClient.dispatcher.executorService.execute(cleanup)
        } catch (_: RejectedExecutionException) {
            Thread(cleanup, "reference-face-api-close").start()
        }
    }

    private fun buildRegisterRequest(image: ByteArray, accessToken: String): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                "reference-face.jpg",
                image.toRequestBody(jpegMediaType),
            )
            .build()
        return Request.Builder()
            .url(serverBaseUrl.resolveOrThrow("/reference-face"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
    }

    private fun buildStatusRequest(accessToken: String): Request =
        Request.Builder()
            .url(serverBaseUrl.resolveOrThrow("/reference-face"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

    private fun buildDeleteRequest(accessToken: String, faceId: String?): Request {
        val collectionUrl = serverBaseUrl.resolveOrThrow("/reference-face")
        val url = faceId?.let { collectionUrl.newBuilder().addPathSegment(it).build() }
            ?: collectionUrl
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
    }

    private data class HttpResult(
        val statusCode: Int,
        val body: String,
    )

    private suspend fun executeAuthenticated(
        accessToken: String,
        refreshAccessToken: suspend () -> String,
        buildRequest: (String) -> Request,
    ): HttpResult {
        var token = accessToken.trim()
        require(token.isNotEmpty()) { "Access token must not be blank." }
        var hasRetriedAfterUnauthorized = false

        while (true) {
            val response = execute(buildRequest(token))
            if (response.statusCode == 401 && !hasRetriedAfterUnauthorized) {
                hasRetriedAfterUnauthorized = true
                token = try {
                    refreshAccessToken().trim()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    throw ReferenceFaceApiException(
                        statusCode = 401,
                        code = "authentication_error",
                        message = "인증 토큰을 갱신하지 못했습니다.",
                        cause = exception,
                    )
                }
                if (token.isEmpty()) {
                    throw ReferenceFaceApiException(
                        statusCode = 401,
                        code = "authentication_error",
                        message = "인증 토큰을 갱신하지 못했습니다.",
                    )
                }
                continue
            }

            return response
        }
    }

    private suspend fun execute(request: Request): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, exception: IOException) {
                    if (!continuation.isActive) return
                    if (call.isCanceled()) {
                        continuation.cancel(CancellationException("Reference face request cancelled."))
                    } else {
                        continuation.resumeWithException(
                            ReferenceFaceApiException(
                                statusCode = null,
                                code = null,
                                message = "얼굴 등록 요청에 실패했습니다.",
                                cause = exception,
                            ),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { currentResponse ->
                            HttpResult(
                                statusCode = currentResponse.code,
                                body = currentResponse.body.string(),
                            )
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (exception: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ReferenceFaceApiException(
                                    statusCode = null,
                                    code = null,
                                    message = "얼굴 등록 요청에 실패했습니다.",
                                    cause = exception,
                                ),
                            )
                        }
                    }
                }
            })
        }

    private fun parseRegistrationResponse(response: HttpResult): ReferenceFaceRegistrationResult {
        if (response.statusCode !in 200..299) {
            throw parseError(response.statusCode, response.body)
        }
        val json = runCatching { JSONObject(response.body) }.getOrElse { exception ->
                throw ReferenceFaceApiException(
                    statusCode = response.statusCode,
                    code = "invalid_response",
                    message = "얼굴 등록 응답을 확인하지 못했습니다.",
                    cause = exception,
                )
            }
        if (json.opt("registered") != true) {
            throw ReferenceFaceApiException(
                statusCode = response.statusCode,
                code = "reference_rejected",
                message = "서버가 기준 얼굴 등록을 확인하지 못했습니다.",
            )
        }
        return ReferenceFaceRegistrationResult(
            registered = true,
            count = json.optInt("count").takeIf { json.has("count") },
        )
    }

    private fun parseStatusResponse(response: HttpResult): ReferenceFaceStatus {
        if (response.statusCode !in 200..299) {
            throw parseError(response.statusCode, response.body)
        }
        val json = parseJson(
            statusCode = response.statusCode,
            body = response.body,
            message = "얼굴 상태 응답을 확인하지 못했습니다.",
        )
        val faces = buildList {
            val faceArray = json.optJSONArray("faces") ?: return@buildList
            for (index in 0 until faceArray.length()) {
                val face = faceArray.optJSONObject(index) ?: continue
                val faceId = face.optString("face_id").trim()
                if (faceId.isNotEmpty()) {
                    add(
                        ReferenceFace(
                            faceId = faceId,
                            registeredAt = face.optNullableString("registered_at"),
                        ),
                    )
                }
            }
        }
        return ReferenceFaceStatus(
            registered = json.opt("registered") == true,
            source = json.optNullableString("source"),
            registeredAt = json.optNullableString("registered_at"),
            count = (json.opt("count") as? Number)?.toInt(),
            faces = faces,
        )
    }

    private fun parseDeleteResponse(response: HttpResult) {
        if (response.statusCode != 204) {
            throw parseError(response.statusCode, response.body)
        }
    }

    private fun parseJson(statusCode: Int, body: String, message: String): JSONObject =
        runCatching { JSONObject(body) }.getOrElse { exception ->
            throw ReferenceFaceApiException(
                statusCode = statusCode,
                code = "invalid_response",
                message = message,
                cause = exception,
            )
        }

    private fun parseError(statusCode: Int, responseBody: String): ReferenceFaceApiException {
        val root = runCatching { JSONObject(responseBody) }.getOrNull()
        val error = root?.optJSONObject("error")
        val code = error?.optString("code")?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (statusCode) {
                401 -> "authentication_error"
                502 -> "ai_unavailable"
                else -> null
            }
        val detailsReason = error?.optJSONObject("details")
            ?.optString("reason")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val message = error?.optString("message")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "얼굴 등록 요청이 거부되었습니다."
        return ReferenceFaceApiException(
            statusCode = statusCode,
            code = code,
            detailsReason = detailsReason,
            message = message,
        )
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    opt(key).takeIf { it is String }
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun String.toHttpUrlOrThrow(): HttpUrl = runCatching { toHttpUrl() }
    .getOrElse { exception ->
        throw IllegalArgumentException("INNOLIVE_SERVER_URL이 올바르지 않습니다.", exception)
    }.also { url ->
        require(url.isHttps) { "INNOLIVE_SERVER_URL은 HTTPS를 사용해야 합니다." }
    }

private fun HttpUrl.resolveOrThrow(path: String): HttpUrl =
    resolve(path) ?: throw IllegalArgumentException("INNOLIVE_SERVER_URL에 경로를 추가할 수 없습니다.")
