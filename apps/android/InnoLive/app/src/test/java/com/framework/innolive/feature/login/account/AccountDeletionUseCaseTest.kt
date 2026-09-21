package com.framework.innolive.feature.login.account

import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            markLocalCleanupPending = { cleanupMarkerPresent = true },
            clearLocalCleanupPending = { cleanupMarkerPresent = false },
        )

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { it.localCleanupPending && !it.isInProgress }
        }

        assertEquals(1, remoteDeletionCount)
        assertEquals(0, authenticationClearCount)
        assertTrue(cleanupMarkerPresent)
        assertTrue(coordinator.state.value.error?.contains("기기 데이터 정리") == true)

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
            initialPendingCleanupSession = session(),
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
            deleteRemoteAccount = { AccountDeletionResult.Failed("old account error") },
            clearLocalAccountData = {},
            clearAuthentication = {},
        )

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { it.error == "old account error" }
        }
        coordinator.resetErrorForAuthenticationChange()

        assertEquals(AccountDeletionState(), coordinator.state.value)
        coordinatorJob.cancel()
    }

    @Test
    fun unexpectedRemoteFailureReturnsToRetryableState() = runBlocking {
        val coordinatorJob = Job()
        val coordinator = AccountDeletionCoordinator(
            scope = CoroutineScope(coordinatorJob + Dispatchers.Default),
            currentSession = { session() },
            deleteRemoteAccount = { error("invalid server configuration") },
            clearLocalAccountData = { error("must not clean local data") },
            clearAuthentication = { error("must not clear authentication") },
        )

        coordinator.delete()
        withTimeout(2_000) {
            coordinator.state.first { it.error != null && !it.isInProgress }
        }

        assertTrue(coordinator.state.value.error?.contains("계정을 삭제하지 못했습니다") == true)
        assertEquals(false, coordinator.state.value.localCleanupPending)
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
