package com.framework.innolive.feature.login.account

import com.framework.innolive.BuildConfig
import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import com.framework.innolive.feature.login.oauth.google.InvalidRefreshTokenException
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

                val body = runCatching { response.body.string() }.getOrDefault("")
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
            throw AccountDeletionException(responseReceived = false, cause = exception)
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
    /** false면 응답을 받지 못해 서버가 삭제했는지 알 수 없다. */
    val responseReceived: Boolean = true,
    cause: Throwable? = null,
) : IOException(message, cause)

internal sealed interface AccountDeletionResult {
    data object Deleted : AccountDeletionResult

    /** 서버가 삭제하지 않았음이 확실하다. 로그인을 유지하고 처음부터 다시 시도한다. */
    data class Failed(val error: UiText) : AccountDeletionResult

    /** 응답을 받지 못해 서버 삭제 여부를 알 수 없다. 원격 삭제 대기 상태로 재시도한다. */
    data class Unconfirmed(val error: UiText) : AccountDeletionResult

    /** refresh token이 무효라 삭제를 재시도할 수 없다. 기존 세션 만료 동작을 따른다. */
    data object SessionExpired : AccountDeletionResult
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
 * Compose 밖에서 삭제 트랜잭션 전체를 소유합니다. 삭제가 시작되면 호출자가 취소돼도 서버
 * 결과에서 로컬 정리로 넘어가는 과정은 중단되지 않습니다. 요청 전에 원격 대기 단계를 저장하므로
 * 응답이 유실되면 멱등한 서버 삭제를 재시도해 상태를 맞출 수 있고, 서버가 명시적으로 실패하면
 * 이 단계를 지우고 로그인을 유지합니다. 로컬 데이터 정리와 인증 정리는 단계가 따로 있어,
 * 재시작하면 끝나지 않은 단계만 이어서 진행합니다.
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
    /** 무효 refresh token으로 로그아웃할 때 방송 연결 등을 닫는다. */
    private val onSessionExpired: () -> Unit = {},
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
                    } catch (_: IOException) {
                        AccountDeletionResult.Unconfirmed(REMOTE_DELETION_FAILURE)
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
                            // 인증 정보는 마지막으로 남는 민감한 로컬 상태입니다. 동기 삭제가 성공할 때까지 복구 표시를
                            // 유지해, 이 시점 전에 프로세스가 종료되면 다음 실행에서 정리를 이어 갑니다.
                            // 인증 정보가 지워진 뒤 표시만 지우지 못하는 경우는 안전합니다. 표시에는 scope 해시만 있어
                            // 삭제된 세션을 복원할 수 없습니다.
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

                    is AccountDeletionResult.Failed -> {
                        // 서버가 실패를 응답했으므로 계정은 남아 있다. 로그인을 유지하고 잠금 marker를
                        // 지운다(account-deletion-v1.md). marker가 남으면 재시작 후에도 잠기므로 기존 상태를 유지한다.
                        if (runCatching { clearPendingDeletion() }.isSuccess) {
                            pendingDeletion = null
                            AccountDeletionState(error = remoteResult.error)
                        } else {
                            AccountDeletionState(
                                pendingPhase = AccountDeletionPhase.REMOTE_DELETION_PENDING,
                                error = remoteResult.error,
                            )
                        }
                    }

                    is AccountDeletionResult.Unconfirmed -> AccountDeletionState(
                        pendingPhase = AccountDeletionPhase.REMOTE_DELETION_PENDING,
                        error = remoteResult.error,
                    )

                    AccountDeletionResult.SessionExpired -> {
                        // 무효 refresh token은 기존 세션 만료 동작을 따른다(account-deletion-v1.md). 서버 계정은
                        // 남아 있으므로 삭제 대기 marker를 지우고, 다음 로그인에서 다시 삭제를 시작한다.
                        pendingDeletion = null
                        runCatching { clearPendingDeletion() }
                        runCatching(onSessionExpired)
                        runCatching(clearAuthentication)
                        AccountDeletionState()
                    }
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
 * 서버가 삭제를 확인할 때까지 로컬 세션을 유지합니다. 인증 실패 응답 뒤에는 새로 갱신한
 * 액세스 토큰으로 한 번만 재시도하며, 그 밖의 실패는 설정 화면에서 다시 시도할 수 있습니다.
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
            } catch (_: InvalidRefreshTokenException) {
                return AccountDeletionResult.SessionExpired
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
            } catch (_: IOException) {
                AccountDeletionResult.Unconfirmed(UiText.Resource(R.string.error_account_deletion))
            } catch (_: Exception) {
                AccountDeletionResult.Failed(UiText.Resource(R.string.error_account_deletion))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            AccountDeletionResult.Unconfirmed(UiText.Resource(R.string.error_account_deletion))
        } catch (_: Exception) {
            AccountDeletionResult.Failed(UiText.Resource(R.string.error_account_deletion))
        }
    }

    private fun AccountDeletionException.toFailure(): AccountDeletionResult {
        val error = message?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
            ?: UiText.Resource(R.string.error_account_deletion)
        return if (responseReceived) {
            AccountDeletionResult.Failed(error)
        } else {
            AccountDeletionResult.Unconfirmed(error)
        }
    }
}
