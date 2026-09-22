package com.framework.innolive.feature.login.account

import com.framework.innolive.BuildConfig
import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import com.framework.innolive.ui.text.UiText
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

    data class Failed(val error: UiText) : AccountDeletionResult
}

internal data class AccountDeletionState(
    val isInProgress: Boolean = false,
    val pendingPhase: AccountDeletionPhase? = null,
    val error: UiText? = null,
) {
    val localCleanupPending: Boolean
        get() = pendingPhase == AccountDeletionPhase.LOCAL_CLEANUP_PENDING ||
            pendingPhase == AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING

    val hasPendingDeletion: Boolean
        get() = pendingPhase != null
}

internal enum class AccountDeletionPhase {
    REMOTE_DELETION_PENDING,
    LOCAL_CLEANUP_PENDING,
    AUTHENTICATION_CLEANUP_PENDING,
}

internal data class PendingAccountDeletion(
    val session: GoogleSessionStore.Session,
    val phase: AccountDeletionPhase,
)

/**
 * Owns the complete deletion transaction outside Compose. Once deletion starts, cancellation of
 * the caller cannot interrupt the transition from the server result to local cleanup. The remote
 * pending phase is persisted before the request so a lost response can be reconciled by retrying
 * the idempotent server deletion. Local-data and authentication cleanup each have their own phase,
 * so a restart resumes only the unfinished step.
 */
internal class AccountDeletionCoordinator(
    private val scope: CoroutineScope,
    private val currentSession: () -> GoogleSessionStore.Session?,
    private val deleteRemoteAccount: suspend (GoogleSessionStore.Session) -> AccountDeletionResult,
    private val clearLocalAccountData: suspend (GoogleSessionStore.Session) -> Unit,
    private val clearAuthentication: () -> Unit,
    initialPendingDeletion: PendingAccountDeletion? = null,
    private val persistPendingDeletion: suspend (
        GoogleSessionStore.Session,
        AccountDeletionPhase,
    ) -> Unit = { _, _ -> },
    private val clearPendingDeletion: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow(
        if (initialPendingDeletion == null) {
            AccountDeletionState()
        } else {
            AccountDeletionState(
                pendingPhase = initialPendingDeletion.phase,
                error = initialPendingDeletion.phase.failureMessage,
            )
        },
    )
    val state: StateFlow<AccountDeletionState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var pendingDeletion: PendingAccountDeletion? = initialPendingDeletion

    fun delete(onRemoteDeletionConfirmed: () -> Unit = {}) {
        if (activeJob?.isActive == true) return
        val deletingSession = pendingDeletion?.session ?: currentSession() ?: return
        _state.value = AccountDeletionState(
            isInProgress = true,
            pendingPhase = pendingDeletion?.phase,
        )

        activeJob = scope.launch {
            withContext(NonCancellable) {
                if (pendingDeletion == null) {
                    try {
                        persistPendingDeletion(
                            deletingSession,
                            AccountDeletionPhase.REMOTE_DELETION_PENDING,
                        )
                        pendingDeletion = PendingAccountDeletion(
                            deletingSession,
                            AccountDeletionPhase.REMOTE_DELETION_PENDING,
                        )
                        _state.value = AccountDeletionState(
                            isInProgress = true,
                            pendingPhase = AccountDeletionPhase.REMOTE_DELETION_PENDING,
                        )
                    } catch (_: Exception) {
                        _state.value = AccountDeletionState(
                            error = DELETION_STATE_PERSISTENCE_FAILURE,
                        )
                        return@withContext
                    }
                }

                val pendingPhase = pendingDeletion?.phase
                val remoteResult = if (
                    pendingPhase != null &&
                    pendingPhase != AccountDeletionPhase.REMOTE_DELETION_PENDING
                ) {
                    AccountDeletionResult.Deleted
                } else {
                    try {
                        deleteRemoteAccount(deletingSession)
                    } catch (_: Exception) {
                        AccountDeletionResult.Failed(REMOTE_DELETION_FAILURE)
                    }
                }

                _state.value = when (remoteResult) {
                    AccountDeletionResult.Deleted -> {
                        try {
                            if (
                                pendingPhase == null ||
                                pendingPhase == AccountDeletionPhase.REMOTE_DELETION_PENDING
                            ) {
                                runCatching(onRemoteDeletionConfirmed)
                                persistPendingDeletion(
                                    deletingSession,
                                    AccountDeletionPhase.LOCAL_CLEANUP_PENDING,
                                )
                                pendingDeletion = PendingAccountDeletion(
                                    deletingSession,
                                    AccountDeletionPhase.LOCAL_CLEANUP_PENDING,
                                )
                            }
                            if (pendingPhase != AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING) {
                                clearLocalAccountData(deletingSession)
                                persistPendingDeletion(
                                    deletingSession,
                                    AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING,
                                )
                                pendingDeletion = PendingAccountDeletion(
                                    deletingSession,
                                    AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING,
                                )
                            }
                            clearAuthentication()
                            pendingDeletion = null
                            // Authentication is the last sensitive local state. Keep the recovery
                            // marker until its synchronous removal has succeeded so a process
                            // death before this point resumes cleanup on the next launch.
                            // A marker-only removal failure after authentication is gone is safe:
                            // it contains only a scope hash and cannot restore the deleted session.
                            runCatching { clearPendingDeletion() }
                            AccountDeletionState()
                        } catch (_: Exception) {
                            runCatching {
                                val retryPhase = pendingDeletion?.phase
                                    ?: AccountDeletionPhase.LOCAL_CLEANUP_PENDING
                                persistPendingDeletion(
                                    deletingSession,
                                    retryPhase,
                                )
                                pendingDeletion = PendingAccountDeletion(
                                    deletingSession,
                                    retryPhase,
                                )
                            }
                            AccountDeletionState(
                                pendingPhase = pendingDeletion?.phase,
                                error = pendingDeletion?.phase?.failureMessage
                                    ?: LOCAL_CLEANUP_FAILURE,
                            )
                        }
                    }

                    is AccountDeletionResult.Failed -> AccountDeletionState(
                        pendingPhase = AccountDeletionPhase.REMOTE_DELETION_PENDING,
                        error = remoteResult.error,
                    )
                }
            }
        }
    }

    fun authenticationChanged(restoredPendingDeletion: PendingAccountDeletion?) {
        if (activeJob?.isActive == true) return
        pendingDeletion = restoredPendingDeletion
        _state.value = restoredPendingDeletion?.let { pending ->
            AccountDeletionState(
                pendingPhase = pending.phase,
                error = pending.phase.failureMessage,
            )
        } ?: AccountDeletionState()
    }

    private companion object {
        val AccountDeletionPhase.failureMessage: UiText
            get() = when (this) {
                AccountDeletionPhase.REMOTE_DELETION_PENDING -> REMOTE_DELETION_FAILURE
                AccountDeletionPhase.LOCAL_CLEANUP_PENDING -> LOCAL_CLEANUP_FAILURE
                AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING ->
                    LOCAL_CLEANUP_FAILURE
            }

        val REMOTE_DELETION_FAILURE = UiText.Resource(R.string.error_account_deletion)
        val DELETION_STATE_PERSISTENCE_FAILURE = UiText.Resource(R.string.error_account_deletion_state)
        val LOCAL_CLEANUP_FAILURE = UiText.Resource(R.string.error_account_cleanup)
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
                return AccountDeletionResult.Failed(UiText.Resource(R.string.error_session_refresh))
            }

            try {
                gateway.delete(refreshedAccessToken)
                AccountDeletionResult.Deleted
            } catch (exception: CancellationException) {
                throw exception
            } catch (retryException: AccountDeletionException) {
                retryException.toFailure()
            } catch (_: Exception) {
                AccountDeletionResult.Failed(UiText.Resource(R.string.error_account_deletion))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            AccountDeletionResult.Failed(UiText.Resource(R.string.error_account_deletion))
        }
    }

    private fun AccountDeletionException.toFailure(): AccountDeletionResult.Failed =
        AccountDeletionResult.Failed(UiText.Resource(R.string.error_account_deletion))
}
