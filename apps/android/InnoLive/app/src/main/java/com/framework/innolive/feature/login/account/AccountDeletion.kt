package com.framework.innolive.feature.login.account

import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal interface AccountDeletionGateway {
    suspend fun delete(accessToken: String)
}

internal class AccountDeletionApi(
    serverUrl: String = BuildConfig.INNOLIVE_SERVER_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : AccountDeletionGateway, AutoCloseable {
    private val serverBaseUrl = serverUrl.trim().trimEnd('/').toHttpUrl().also { url ->
        require(url.isHttps) { "INNOLIVE_SERVER_URL must use HTTPS." }
    }

    override suspend fun delete(accessToken: String): Unit = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank()) { "Access token must not be blank." }
        val request = Request.Builder()
            .url(checkNotNull(serverBaseUrl.resolve("/auth/me")))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 204) return@withContext

                val body = response.body.string()
                val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
                throw AccountDeletionException(
                    code = error?.optString("code")?.takeIf(String::isNotBlank)
                        ?: if (response.code == 401) "unauthorized" else null,
                    message = error?.optString("message")?.takeIf(String::isNotBlank),
                )
            }
        } catch (exception: AccountDeletionException) {
            throw exception
        } catch (exception: IOException) {
            throw AccountDeletionException(cause = exception)
        }
    }

    override fun close() {
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }
}

internal class AccountDeletionException(
    val code: String? = null,
    message: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

internal sealed interface AccountDeletionResult {
    data object Deleted : AccountDeletionResult

    data class Failed(val message: String) : AccountDeletionResult
}

internal data class AccountDeletionState(
    val isInProgress: Boolean = false,
    val localCleanupPending: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the complete deletion transaction outside Compose. Once deletion starts, cancellation of
 * the caller cannot interrupt the transition from the server result to local cleanup. A failed
 * local cleanup retains the session and retries only that cleanup on the next request.
 */
internal class AccountDeletionCoordinator(
    private val scope: CoroutineScope,
    private val currentSession: () -> GoogleSessionStore.Session?,
    private val deleteRemoteAccount: suspend (GoogleSessionStore.Session) -> AccountDeletionResult,
    private val clearLocalAccountData: suspend (GoogleSessionStore.Session) -> Unit,
    private val clearAuthentication: () -> Unit,
    initialPendingCleanupSession: GoogleSessionStore.Session? = null,
    private val markLocalCleanupPending: (GoogleSessionStore.Session) -> Unit = {},
    private val clearLocalCleanupPending: () -> Unit = {},
) {
    private val _state = MutableStateFlow(
        if (initialPendingCleanupSession == null) {
            AccountDeletionState()
        } else {
            AccountDeletionState(
                localCleanupPending = true,
                error = LOCAL_CLEANUP_FAILURE_MESSAGE,
            )
        },
    )
    val state: StateFlow<AccountDeletionState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var pendingCleanupSession: GoogleSessionStore.Session? =
        initialPendingCleanupSession

    fun delete() {
        if (activeJob?.isActive == true) return
        val cleanupOnly = pendingCleanupSession != null
        val deletingSession = pendingCleanupSession ?: currentSession() ?: return
        _state.value = AccountDeletionState(
            isInProgress = true,
            localCleanupPending = cleanupOnly,
        )

        activeJob = scope.launch {
            withContext(NonCancellable) {
                val remoteResult = if (cleanupOnly) {
                    AccountDeletionResult.Deleted
                } else {
                    try {
                        deleteRemoteAccount(deletingSession)
                    } catch (_: Exception) {
                        AccountDeletionResult.Failed(REMOTE_DELETION_FAILURE_MESSAGE)
                    }
                }

                _state.value = when (remoteResult) {
                    AccountDeletionResult.Deleted -> {
                        pendingCleanupSession = deletingSession
                        try {
                            markLocalCleanupPending(deletingSession)
                            clearLocalAccountData(deletingSession)
                            clearLocalCleanupPending()
                            clearAuthentication()
                            pendingCleanupSession = null
                            AccountDeletionState()
                        } catch (_: Exception) {
                            runCatching { markLocalCleanupPending(deletingSession) }
                            AccountDeletionState(
                                localCleanupPending = true,
                                error = LOCAL_CLEANUP_FAILURE_MESSAGE,
                            )
                        }
                    }

                    is AccountDeletionResult.Failed -> AccountDeletionState(
                        error = remoteResult.message,
                    )
                }
            }
        }
    }

    fun resetErrorForAuthenticationChange() {
        if (activeJob?.isActive == true || pendingCleanupSession != null) return
        _state.value = AccountDeletionState()
    }

    private companion object {
        const val REMOTE_DELETION_FAILURE_MESSAGE =
            "계정을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요."
        const val LOCAL_CLEANUP_FAILURE_MESSAGE =
            "서버 계정은 삭제됐지만 기기 데이터 정리에 실패했습니다. 다시 시도해 주세요."
    }
}

/**
 * Preserves the local session until the server confirms deletion. The one
 * permitted retry uses a newly refreshed access token after an unauthorized
 * response; every other failure remains retryable from the settings screen.
 */
internal class AccountDeletionUseCase(
    private val gateway: AccountDeletionGateway,
    private val refreshAccessToken: suspend () -> String,
) {
    suspend fun delete(accessToken: String): AccountDeletionResult {
        return try {
            gateway.delete(accessToken)
            AccountDeletionResult.Deleted
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: AccountDeletionException) {
            if (exception.code != "unauthorized") return exception.toFailure()

            val refreshedAccessToken = try {
                refreshAccessToken()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                return AccountDeletionResult.Failed("로그인 상태를 갱신하지 못했습니다. 잠시 후 다시 시도해 주세요.")
            }

            try {
                gateway.delete(refreshedAccessToken)
                AccountDeletionResult.Deleted
            } catch (exception: CancellationException) {
                throw exception
            } catch (retryException: AccountDeletionException) {
                retryException.toFailure()
            } catch (_: Exception) {
                AccountDeletionResult.Failed("계정을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.")
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            AccountDeletionResult.Failed("계정을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.")
        }
    }

    private fun AccountDeletionException.toFailure(): AccountDeletionResult.Failed =
        AccountDeletionResult.Failed(
            message?.takeIf(String::isNotBlank)
                ?: "계정을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        )
}
