package com.framework.innolive.feature.login.account

import kotlinx.coroutines.runBlocking
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

    private class FakeGateway(vararg outcomes: AccountDeletionException?) : AccountDeletionGateway {
        private val outcomes = outcomes.toMutableList()
        val accessTokens = mutableListOf<String>()

        override suspend fun delete(accessToken: String) {
            accessTokens += accessToken
            outcomes.removeFirstOrNull()?.let { throw it }
        }
    }
}
