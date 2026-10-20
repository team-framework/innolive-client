package com.framework.innolive.feature.login.account

import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import com.framework.innolive.feature.login.oauth.google.InvalidRefreshTokenException
import com.framework.innolive.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class AccountDeletionUseCaseTest {
    @Test
    fun unauthorizedRequestRefreshesAndRetriesOnce() = runBlocking {
        val gateway = FakeGateway(
            AccountDeletionException(code = "unauthorized"),
            null,
        )
        var refreshCount = 0

        val result = AccountDeletionUseCase(gateway) {
            refreshCount += 1
            "refreshed-access-token"
        }.delete("expired-access-token")

        assertEquals(AccountDeletionResult.Deleted, result)
        assertEquals(1, refreshCount)
        assertEquals(listOf("expired-access-token", "refreshed-access-token"), gateway.accessTokens)
    }

    @Test
    fun nonUnauthorizedFailureDoesNotRefreshOrReportDeletion() = runBlocking {
        val gateway = FakeGateway(AccountDeletionException(code = "withdrawal_in_progress"))
        var refreshCount = 0

        val result = AccountDeletionUseCase(gateway) {
            refreshCount += 1
            "unused"
        }.delete("access-token")

        assertTrue(result is AccountDeletionResult.Failed)
        assertEquals(0, refreshCount)
        assertEquals(listOf("access-token"), gateway.accessTokens)
    }

    @Test
    fun serverSuppliedDeletionMessageIsRetainedAsDynamicUiText() = runBlocking {
        val result = AccountDeletionUseCase(
            FakeGateway(AccountDeletionException(code = "withdrawal_unavailable", message = "internal server detail")),
        ) { "unused" }.delete("access-token")

        assertEquals(
            AccountDeletionResult.Failed(UiText.Dynamic("internal server detail")),
            result,
        )
    }

    @Test
    fun deletionApiPassesServerFreeFormMessageToTheUiTextBoundary() = runBlocking {
        var request: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder()
                .request(checkNotNull(request))
                .protocol(Protocol.HTTP_1_1)
                .code(409)
                .message("fixture")
                .body(
                    """{"error":{"code":"withdrawal_in_progress","message":"계정 삭제를 처리 중입니다."}}"""
                        .toResponseBody(),
                )
                .build()
        }.build()

        val result = AccountDeletionApi("https://example.test", client).use { api ->
            AccountDeletionUseCase(api) { error("재인증하면 안 됩니다.") }.delete("access-token")
        }

        assertEquals(
            AccountDeletionResult.Failed(UiText.Dynamic("계정 삭제를 처리 중입니다.")),
            result,
        )
        assertEquals("DELETE", request?.method)
        assertEquals("/auth/me", request?.url?.encodedPath)
    }

    @Test
    fun retryFailureDoesNotAttemptAnotherRefresh() = runBlocking {
        val gateway = FakeGateway(
            AccountDeletionException(code = "unauthorized"),
            AccountDeletionException(code = "withdrawal_unavailable"),
        )
        var refreshCount = 0

        val result = AccountDeletionUseCase(gateway) {
            refreshCount += 1
            "refreshed-access-token"
        }.delete("expired-access-token")

        assertTrue(result is AccountDeletionResult.Failed)
        assertEquals(1, refreshCount)
        assertEquals(2, gateway.accessTokens.size)
    }

    @Test
    fun callerCancellationAfterRequestStartsStillCompletesLocalCleanup() = runBlocking {
        val coordinatorJob = Job()
        val requestStarted = CompletableDeferred<Unit>()
        val finishRequest = CompletableDeferred<Unit>()
        var localCleanupCount = 0
        var authenticationClearCount = 0
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                requestStarted.complete(Unit)
                finishRequest.await()
                AccountDeletionResult.Deleted
            },
            clearLocalAccountData = { localCleanupCount += 1 },
            clearAuthentication = { authenticationClearCount += 1 },
        )

        coordinator.delete()
        requestStarted.await()
        coordinatorJob.cancel()
        finishRequest.complete(Unit)
        withTimeout(2_000) {
            coordinator.state.first { !it.isInProgress }
        }

        assertEquals(1, localCleanupCount)
        assertEquals(1, authenticationClearCount)
        assertEquals(null, coordinator.state.value.error)
    }

    @Test
    fun remotePendingStateIsPersistedBeforeServerDeletionStarts() = runBlocking {
        val coordinatorJob = Job()
        val events = mutableListOf<String>()
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                events += "remote"
                AccountDeletionResult.Deleted
            },
            clearLocalAccountData = { events += "local" },
            clearAuthentication = { events += "auth" },
            persistPendingDeletion = { _, phase -> events += "persist:$phase" },
            clearPendingDeletion = { events += "clear-marker" },
        )

        coordinator.delete()
        withTimeout(2_000) { coordinator.state.first { !it.isInProgress } }

        assertEquals(
            listOf(
                "persist:REMOTE_DELETION_PENDING",
                "remote",
                "persist:LOCAL_CLEANUP_PENDING",
                "local",
                "persist:AUTHENTICATION_CLEANUP_PENDING",
                "auth",
                "clear-marker",
            ),
            events,
        )
        coordinatorJob.cancel()
    }

    @Test
    fun authenticationFailureKeepsRecoveryMarkerForNextLaunch() = runBlocking {
        val coordinatorJob = Job()
        var markerPresent = false
        var authenticationClearCount = 0
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = { AccountDeletionResult.Deleted },
            clearLocalAccountData = {},
            clearAuthentication = {
                authenticationClearCount += 1
                error("authentication storage failure")
            },
            persistPendingDeletion = { _, _ -> markerPresent = true },
            clearPendingDeletion = { markerPresent = false },
        )

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { it.localCleanupPending && !it.isInProgress }
        }

        assertEquals(1, authenticationClearCount)
        assertTrue(markerPresent)
        assertEquals(
            AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING,
            coordinator.state.value.pendingPhase,
        )
        assertEquals(UiText.Resource(R.string.error_account_cleanup), coordinator.state.value.error)
        coordinatorJob.cancel()
    }

    @Test
    fun markerRemovalFailureAfterAuthenticationDoesNotUndoCompletedDeletion() = runBlocking {
        val coordinatorJob = Job()
        val events = mutableListOf<String>()
        var authenticationPresent = true
        var markerPresent = false
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = { AccountDeletionResult.Deleted },
            clearLocalAccountData = { events += "local" },
            clearAuthentication = {
                events += "auth"
                authenticationPresent = false
            },
            persistPendingDeletion = { _, phase ->
                events += "persist:$phase"
                markerPresent = true
            },
            clearPendingDeletion = {
                events += "clear-marker"
                error("marker storage failure")
            },
        )

        coordinator.delete()
        withTimeout(2_000) { coordinator.state.first { !it.isInProgress } }

        assertEquals(
            listOf(
                "persist:REMOTE_DELETION_PENDING",
                "persist:LOCAL_CLEANUP_PENDING",
                "local",
                "persist:AUTHENTICATION_CLEANUP_PENDING",
                "auth",
                "clear-marker",
            ),
            events,
        )
        assertEquals(false, authenticationPresent)
        assertTrue(markerPresent)
        assertEquals(AccountDeletionState(), coordinator.state.value)
        coordinatorJob.cancel()
    }

    @Test
    fun persistedAuthenticationCleanupDoesNotRepeatLocalCleanupForOldSession() = runBlocking {
        val coordinatorJob = Job()
        var remoteDeletionCount = 0
        var localCleanupCount = 0
        var authenticationClearCount = 0
        var markerPresent = true
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                remoteDeletionCount += 1
                AccountDeletionResult.Deleted
            },
            clearLocalAccountData = { localCleanupCount += 1 },
            clearAuthentication = { authenticationClearCount += 1 },
            initialPendingDeletion = PendingAccountDeletion(
                session(),
                AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING,
            ),
            clearPendingDeletion = { markerPresent = false },
        )

        coordinator.delete()
        withTimeout(2_000) { coordinator.state.first { !it.isInProgress } }

        assertEquals(0, remoteDeletionCount)
        assertEquals(0, localCleanupCount)
        assertEquals(1, authenticationClearCount)
        assertEquals(false, markerPresent)
        assertEquals(AccountDeletionState(), coordinator.state.value)
        coordinatorJob.cancel()
    }

    @Test
    fun remoteFailureDoesNotCloseBroadcastAndRemainsRetryableAfterRestart() = runBlocking {
        val coordinatorJob = Job()
        var closeCount = 0
        var remoteCount = 0
        val firstSession = session()
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { firstSession },
            deleteRemoteAccount = {
                remoteCount += 1
                if (remoteCount == 1) {
                    AccountDeletionResult.Unconfirmed(UiText.Resource(R.string.error_account_deletion))
                } else {
                    AccountDeletionResult.Deleted
                }
            },
            clearLocalAccountData = {},
            clearAuthentication = {},
            initialPendingDeletion = PendingAccountDeletion(
                firstSession,
                AccountDeletionPhase.REMOTE_DELETION_PENDING,
            ),
        )

        coordinator.delete { closeCount += 1 }
        withTimeout(2_000) {
            coordinator.state.first { it.error == UiText.Resource(R.string.error_account_deletion) }
        }
        assertEquals(0, closeCount)
        assertEquals(AccountDeletionPhase.REMOTE_DELETION_PENDING, coordinator.state.value.pendingPhase)

        coordinator.delete { closeCount += 1 }
        withTimeout(2_000) { coordinator.state.first { remoteCount == 2 && !it.isInProgress } }
        assertEquals(1, closeCount)
        assertEquals(2, remoteCount)
        coordinatorJob.cancel()
    }

    @Test
    fun localCleanupFailureKeepsAuthenticationAndRetrySkipsServerDeletion() = runBlocking {
        val coordinatorJob = Job()
        var remoteDeletionCount = 0
        var localCleanupCount = 0
        var authenticationClearCount = 0
        var cleanupMarkerPresent = false
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                remoteDeletionCount += 1
                AccountDeletionResult.Deleted
            },
            clearLocalAccountData = {
                localCleanupCount += 1
                if (localCleanupCount == 1) error("disk failure")
            },
            clearAuthentication = { authenticationClearCount += 1 },
            persistPendingDeletion = { _, _ -> cleanupMarkerPresent = true },
            clearPendingDeletion = { cleanupMarkerPresent = false },
        )

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { it.localCleanupPending && !it.isInProgress }
        }

        assertEquals(1, remoteDeletionCount)
        assertEquals(0, authenticationClearCount)
        assertTrue(cleanupMarkerPresent)
        assertEquals(UiText.Resource(R.string.error_account_cleanup), coordinator.state.value.error)

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first {
                localCleanupCount == 2 && !it.isInProgress && !it.localCleanupPending
            }
        }

        assertEquals(1, remoteDeletionCount)
        assertEquals(1, authenticationClearCount)
        assertEquals(false, cleanupMarkerPresent)
        coordinatorJob.cancel()
    }

    @Test
    fun persistedCleanupStateRetriesWithoutDeletingServerAccountAgain() = runBlocking {
        val coordinatorJob = Job()
        var remoteDeletionCount = 0
        var localCleanupCount = 0
        var authenticationClearCount = 0
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                remoteDeletionCount += 1
                AccountDeletionResult.Deleted
            },
            clearLocalAccountData = { localCleanupCount += 1 },
            clearAuthentication = { authenticationClearCount += 1 },
            initialPendingDeletion = PendingAccountDeletion(
                session(),
                AccountDeletionPhase.LOCAL_CLEANUP_PENDING,
            ),
        )

        assertTrue(coordinator.state.value.localCleanupPending)
        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { !it.isInProgress && !it.localCleanupPending }
        }

        assertEquals(0, remoteDeletionCount)
        assertEquals(1, localCleanupCount)
        assertEquals(1, authenticationClearCount)
        coordinatorJob.cancel()
    }

    @Test
    fun authenticationChangeClearsPreviousRemoteDeletionError() = runBlocking {
        val coordinatorJob = Job()
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                AccountDeletionResult.Failed(UiText.Resource(R.string.error_account_deletion))
            },
            clearLocalAccountData = {},
            clearAuthentication = {},
        )

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { it.error == UiText.Resource(R.string.error_account_deletion) }
        }
        coordinator.authenticationChanged(null)

        assertEquals(AccountDeletionState(), coordinator.state.value)
        coordinatorJob.cancel()
    }

    @Test
    fun unexpectedRemoteFailureReturnsToRetryableState() = runBlocking {
        val coordinatorJob = Job()
        var markerPresent = false
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = { error("invalid server configuration") },
            clearLocalAccountData = { error("must not clean local data") },
            clearAuthentication = { error("must not clear authentication") },
            persistPendingDeletion = { _, _ -> markerPresent = true },
            clearPendingDeletion = { markerPresent = false },
        )

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { it.error != null && !it.isInProgress }
        }

        assertEquals(UiText.Resource(R.string.error_account_deletion), coordinator.state.value.error)
        assertEquals(false, coordinator.state.value.localCleanupPending)
        assertEquals(null, coordinator.state.value.pendingPhase)
        assertEquals(false, markerPresent)
        coordinatorJob.cancel()
    }

    @Test
    fun remoteTransportExceptionKeepsRemotePendingForRetry() = runBlocking {
        val coordinatorJob = Job()
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = { throw IOException("connection reset") },
            clearLocalAccountData = { error("must not clean local data") },
            clearAuthentication = { error("must not clear authentication") },
            clearPendingDeletion = { error("must keep the remote pending marker") },
        )

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { it.error != null && !it.isInProgress }
        }

        assertEquals(UiText.Resource(R.string.error_account_deletion), coordinator.state.value.error)
        assertEquals(AccountDeletionPhase.REMOTE_DELETION_PENDING, coordinator.state.value.pendingPhase)
        coordinatorJob.cancel()
    }


    // --- 삭제 API 응답 처리 (account-deletion-v1.md) ---

    /** 204 No Content만 성공이다. 본문이 없는 DELETE 요청에 Bearer 토큰을 싣는다. */
    @Test fun onlyNoContentConfirmsDeletionAndTheRequestCarriesBearerWithoutBody() = runBlocking {
        val requests = mutableListOf<Request>()
        var code = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fixture")
                .body((if (code == 204) "" else "{}").toResponseBody())
                .build()
        }.build()

        AccountDeletionApi("https://example.test", client).use { api ->
            val useCase = AccountDeletionUseCase(api) { error("재인증하면 안 됩니다.") }
            assertEquals(
                AccountDeletionResult.Failed(UiText.Resource(R.string.error_account_deletion)),
                useCase.delete("access-token"),
            )
            code = 204
            assertEquals(AccountDeletionResult.Deleted, useCase.delete("access-token"))
        }

        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("DELETE", request.method)
            assertEquals("/auth/me", request.url.encodedPath)
            assertEquals("Bearer access-token", request.header("Authorization"))
            assertEquals(0L, request.body?.contentLength() ?: 0L)
        }
    }

    /** 401은 본문의 코드와 무관하게 갱신 후 한 번 재시도한다. */
    @Test fun unauthorizedWithoutErrorBodyStillRefreshesExactlyOnce() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (requests.size == 1) 401 else 204)
                .message("fixture")
                .body("".toResponseBody())
                .build()
        }.build()
        var refreshCount = 0

        val result = AccountDeletionApi("https://example.test", client).use { api ->
            AccountDeletionUseCase(api) {
                refreshCount += 1
                "refreshed-token"
            }.delete("expired-token")
        }

        assertEquals(AccountDeletionResult.Deleted, result)
        assertEquals(1, refreshCount)
        assertEquals(listOf("Bearer expired-token", "Bearer refreshed-token"), requests.map { it.header("Authorization") })
    }

    /** 409·502·503과 비정상 본문은 로컬 세션을 유지하는 재시도 가능 실패다. */
    @Test fun conflictFixtureAndServerFailuresKeepTheSessionRetryable() = runBlocking {
        val root = generateSequence(File(checkNotNull(System.getProperty("innolive.android.resDir")))) { it.parentFile }
            .first { File(it, "contracts/fixtures").isDirectory }
        val conflict = File(root, "contracts/fixtures/account-deletion-conflict.v1.json").readText()
        val cases = listOf(
            409 to conflict,
            502 to """{"error":{"code":"withdrawal_failed"}}""",
            503 to "",
            500 to "not json",
        )

        for ((status, body) in cases) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("fixture")
                    .body(body.toResponseBody())
                    .build()
            }.build()

            val result = AccountDeletionApi("https://example.test", client).use { api ->
                AccountDeletionUseCase(api) { error("재인증하면 안 됩니다.") }.delete("access-token")
            }

            val expected = if (status == 409) {
                UiText.Dynamic("Account deletion is already in progress. Retry shortly.")
            } else {
                UiText.Resource(R.string.error_account_deletion)
            }
            assertEquals("HTTP $status", AccountDeletionResult.Failed(expected), result)
        }
    }

    /** 갱신 실패는 로컬 세션을 유지하며 재시도 요청을 보내지 않는다. */
    @Test fun refreshFailureKeepsTheSessionWithoutRetryingTheRequest() = runBlocking {
        val gateway = FakeGateway(AccountDeletionException(code = "unauthorized"), null)

        val result = AccountDeletionUseCase(gateway) { throw IOException("refresh unavailable") }
            .delete("expired-token")

        assertEquals(AccountDeletionResult.Failed(UiText.Resource(R.string.error_session_refresh)), result)
        assertEquals(listOf("expired-token"), gateway.accessTokens)
    }

    @Test fun blankAccessTokenNeverReachesTheServer() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += 1
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("fixture")
                .body("".toResponseBody())
                .build()
        }.build()

        val result = AccountDeletionApi("https://example.test", client).use { api ->
            AccountDeletionUseCase(api) { "unused" }.delete("   ")
        }

        assertTrue(result is AccountDeletionResult.Failed)
        assertEquals(0, requests)
    }

    // --- Coordinator 경합·실패 경로 ---

    @Test fun secondDeleteWhileTheFirstIsInFlightDoesNotSendAnotherRequest() = runBlocking {
        val coordinatorJob = Job()
        val requestStarted = CompletableDeferred<Unit>()
        val finishRequest = CompletableDeferred<Unit>()
        var remoteCount = 0
        var confirmedCount = 0
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                remoteCount += 1
                requestStarted.complete(Unit)
                finishRequest.await()
                AccountDeletionResult.Deleted
            },
            clearLocalAccountData = {},
            clearAuthentication = {},
        )

        coordinator.delete { confirmedCount += 1 }
        requestStarted.await()
        coordinator.delete { confirmedCount += 1 }
        assertTrue(coordinator.state.value.isInProgress)
        finishRequest.complete(Unit)
        withTimeout(2_000) { coordinator.state.first { !it.isInProgress } }

        assertEquals(1, remoteCount)
        assertEquals(1, confirmedCount)
        assertEquals(AccountDeletionState(), coordinator.state.value)
        coordinatorJob.cancel()
    }

    @Test fun pendingMarkerPersistenceFailureSkipsTheServerAndStaysRetryable() = runBlocking {
        val coordinatorJob = Job()
        var persistFails = true
        var remoteCount = 0
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                remoteCount += 1
                AccountDeletionResult.Deleted
            },
            clearLocalAccountData = {},
            clearAuthentication = {},
            persistPendingDeletion = { _, _ -> if (persistFails) error("disk full") },
        )

        coordinator.delete()
        withTimeout(2_000) { coordinator.state.first { it.error != null && !it.isInProgress } }
        assertEquals(UiText.Resource(R.string.error_account_deletion_state), coordinator.state.value.error)
        assertEquals(null, coordinator.state.value.pendingPhase)
        assertEquals(0, remoteCount)

        persistFails = false
        coordinator.delete()
        withTimeout(2_000) { coordinator.state.first { remoteCount == 1 && !it.isInProgress } }
        assertEquals(AccountDeletionState(), coordinator.state.value)
        coordinatorJob.cancel()
    }

    @Test fun unconfirmedRemoteDeletionRunsNoCleanupAndKeepsRemotePending() = runBlocking {
        val coordinatorJob = Job()
        var localCleanupCount = 0
        var authenticationClearCount = 0
        var confirmedCount = 0
        val persisted = mutableListOf<AccountDeletionPhase>()
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = { AccountDeletionResult.Unconfirmed(UiText.Dynamic("server detail")) },
            clearLocalAccountData = { localCleanupCount += 1 },
            clearAuthentication = { authenticationClearCount += 1 },
            persistPendingDeletion = { _, phase -> persisted += phase },
            clearPendingDeletion = { error("must keep the remote pending marker") },
        )

        coordinator.delete { confirmedCount += 1 }
        withTimeout(2_000) { coordinator.state.first { it.error != null && !it.isInProgress } }

        assertEquals(0, localCleanupCount)
        assertEquals(0, authenticationClearCount)
        assertEquals(0, confirmedCount)
        assertEquals(listOf(AccountDeletionPhase.REMOTE_DELETION_PENDING), persisted)
        assertEquals(AccountDeletionPhase.REMOTE_DELETION_PENDING, coordinator.state.value.pendingPhase)
        assertEquals(UiText.Dynamic("server detail"), coordinator.state.value.error)
        coordinatorJob.cancel()
    }

    /** account-deletion-v1.md: 서버가 실패를 응답하면 계정이 남아 있으므로 로그인을 유지하고 잠그지 않는다. */
    @Test fun explicitServerFailureClearsPendingMarkerAndKeepsLoginUsable() = runBlocking {
        val coordinatorJob = Job()
        val events = mutableListOf<String>()
        var confirmedCount = 0
        var markerPresent = true
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                events += "remote"
                AccountDeletionResult.Failed(UiText.Dynamic("server detail"))
            },
            clearLocalAccountData = { events += "local" },
            clearAuthentication = { events += "auth" },
            // 이전 실행의 전송 오류로 남은 marker에서 재시도해도 명시적 실패면 잠금을 푼다.
            initialPendingDeletion = PendingAccountDeletion(
                session(),
                AccountDeletionPhase.REMOTE_DELETION_PENDING,
            ),
            persistPendingDeletion = { _, phase ->
                events += "persist:$phase"
                markerPresent = true
            },
            clearPendingDeletion = {
                events += "clear-marker"
                markerPresent = false
            },
        )

        coordinator.delete { confirmedCount += 1 }
        withTimeout(2_000) { coordinator.state.first { it.error != null && !it.isInProgress } }

        assertEquals(listOf("remote", "clear-marker"), events)
        assertEquals(false, markerPresent)
        assertEquals(0, confirmedCount)
        assertEquals(false, coordinator.state.value.hasPendingDeletion)
        assertEquals(UiText.Dynamic("server detail"), coordinator.state.value.error)

        // 다음 시도는 현재 세션으로 새 삭제를 시작한다.
        coordinator.delete { confirmedCount += 1 }
        withTimeout(2_000) { coordinator.state.first { events.size == 5 && !it.isInProgress } }
        assertEquals(
            listOf("remote", "clear-marker", "persist:REMOTE_DELETION_PENDING", "remote", "clear-marker"),
            events,
        )
        assertEquals(false, coordinator.state.value.hasPendingDeletion)
        coordinatorJob.cancel()
    }

    @Test fun explicitServerFailureKeepsRemotePendingWhenMarkerRemovalFails() = runBlocking {
        val coordinatorJob = Job()
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = { AccountDeletionResult.Failed(UiText.Dynamic("server detail")) },
            clearLocalAccountData = {},
            clearAuthentication = { error("must not clear authentication") },
            clearPendingDeletion = { error("marker storage failure") },
        )

        coordinator.delete()
        withTimeout(2_000) { coordinator.state.first { it.error != null && !it.isInProgress } }

        assertEquals(AccountDeletionPhase.REMOTE_DELETION_PENDING, coordinator.state.value.pendingPhase)
        assertEquals(UiText.Dynamic("server detail"), coordinator.state.value.error)
        coordinatorJob.cancel()
    }

    /** 응답을 받지 못한 요청은 서버 삭제 여부를 알 수 없어 원격 삭제 대기 상태로 남긴다. */
    @Test fun transportFailureIsUnconfirmedWhileServerErrorResponseIsFailed() = runBlocking {
        var transportFails = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (transportFails) throw IOException("connection reset")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("fixture")
                .body("""{"error":{"code":"withdrawal_unavailable"}}""".toResponseBody())
                .build()
        }.build()

        AccountDeletionApi("https://example.test", client).use { api ->
            val useCase = AccountDeletionUseCase(api) { error("재인증하면 안 됩니다.") }
            assertEquals(
                AccountDeletionResult.Unconfirmed(UiText.Resource(R.string.error_account_deletion)),
                useCase.delete("access-token"),
            )
            transportFails = false
            assertEquals(
                AccountDeletionResult.Failed(UiText.Resource(R.string.error_account_deletion)),
                useCase.delete("access-token"),
            )
        }
    }

    @Test fun transportFailureOnRetryAfterRefreshIsUnconfirmed() = runBlocking {
        val gateway = FakeGateway(
            AccountDeletionException(code = "unauthorized"),
            AccountDeletionException(responseReceived = false, cause = IOException("timeout")),
        )

        val result = AccountDeletionUseCase(gateway) { "refreshed-access-token" }.delete("expired-access-token")

        assertEquals(
            AccountDeletionResult.Unconfirmed(UiText.Resource(R.string.error_account_deletion)),
            result,
        )
        assertEquals(2, gateway.accessTokens.size)
    }

    @Test fun authenticationChangeDuringDeletionIsIgnoredUntilItFinishes() = runBlocking {
        val coordinatorJob = Job()
        val requestStarted = CompletableDeferred<Unit>()
        val finishRequest = CompletableDeferred<Unit>()
        var authenticationClearCount = 0
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = {
                requestStarted.complete(Unit)
                finishRequest.await()
                AccountDeletionResult.Deleted
            },
            clearLocalAccountData = {},
            clearAuthentication = { authenticationClearCount += 1 },
        )

        coordinator.delete()
        requestStarted.await()
        coordinator.authenticationChanged(null)
        assertTrue(coordinator.state.value.isInProgress)
        finishRequest.complete(Unit)
        withTimeout(2_000) { coordinator.state.first { !it.isInProgress } }

        assertEquals(1, authenticationClearCount)
        assertEquals(AccountDeletionState(), coordinator.state.value)
        coordinatorJob.cancel()
    }

    /** account-deletion-v1.md: 무효 refresh token은 기존 세션 만료 동작을 따르고, 일시 갱신 실패는 로컬 세션을 유지한다. */
    @Test fun invalidRefreshTokenExpiresTheSessionWhileTransientRefreshFailureKeepsIt() = runBlocking {
        val expired = FakeGateway(AccountDeletionException(code = "unauthorized"), null)
        assertEquals(
            AccountDeletionResult.SessionExpired,
            AccountDeletionUseCase(expired) { throw InvalidRefreshTokenException(401) }.delete("expired-token"),
        )
        assertEquals(listOf("expired-token"), expired.accessTokens)

        val transient = FakeGateway(AccountDeletionException(code = "unauthorized"), null)
        assertEquals(
            AccountDeletionResult.Failed(UiText.Resource(R.string.error_session_refresh)),
            AccountDeletionUseCase(transient) { throw IOException("HTTP 503") }.delete("expired-token"),
        )
        assertEquals(listOf("expired-token"), transient.accessTokens)
    }

    @Test fun expiredSessionDuringDeletionClearsMarkerAndAuthenticationWithoutLocalCleanup() = runBlocking {
        val coordinatorJob = Job()
        val events = mutableListOf<String>()
        var confirmedCount = 0
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = { AccountDeletionResult.SessionExpired },
            clearLocalAccountData = { events += "local" },
            clearAuthentication = { events += "auth" },
            persistPendingDeletion = { _, phase -> events += "persist:$phase" },
            clearPendingDeletion = { events += "clear-marker" },
            onSessionExpired = { events += "expired" },
        )

        coordinator.delete { confirmedCount += 1 }
        withTimeout(2_000) { coordinator.state.first { !it.isInProgress } }

        assertEquals(listOf("persist:REMOTE_DELETION_PENDING", "clear-marker", "expired", "auth"), events)
        assertEquals(0, confirmedCount)
        assertEquals(AccountDeletionState(), coordinator.state.value)
        // 다음 로그인에서는 새 삭제로 시작한다.
        coordinator.authenticationChanged(null)
        assertEquals(false, coordinator.state.value.hasPendingDeletion)
        coordinatorJob.cancel()
    }

    private fun session() = GoogleSessionStore.Session(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        tokenType = "Bearer",
        expiresIn = 3_600,
        refreshExpiresIn = 7_200,
        profileName = "User",
        profileEmail = "user@example.com",
    )

    private class FakeGateway(vararg outcomes: AccountDeletionException?) : AccountDeletionGateway {
        private val outcomes = outcomes.toMutableList()
        val accessTokens = mutableListOf<String>()

        override suspend fun delete(accessToken: String) {
            accessTokens += accessToken
            outcomes.removeFirstOrNull()?.let { throw it }
        }
    }
}
