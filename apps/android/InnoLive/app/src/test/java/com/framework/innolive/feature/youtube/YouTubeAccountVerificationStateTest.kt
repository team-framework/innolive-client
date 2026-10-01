package com.framework.innolive.feature.youtube

import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.ui.text.UiText
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class YouTubeAccountVerificationStateTest {
    @Test
    fun lostReplacementResponseInvalidatesOldAccountAndRequiresServerRefresh() = runBlocking {
        val memory = YouTubeVerificationMemory().apply {
            state.value = YouTubeAccountVerificationState.VERIFIED
            verifiedProfileEmail.value = "viewer@example.com"
            suppressRefreshOnce.value = true
        }
        val oldAccount = StreamingAccount("youtube", "old-channel", "Old Channel", false)
        val lostResponse = IOException("Connection response lost after server committed replacement")

        try {
            memory.connectAccount { throw lostResponse }
            fail("The transport failure must propagate")
        } catch (failure: IOException) {
            assertTrue(failure === lostResponse)
        }

        assertFalse(hasVerifiedYouTubeAccount(
            oldAccount, memory.state.value, memory.verifiedProfileEmail.value, "viewer@example.com",
        ))
        assertEquals(YouTubeAccountVerificationState.UNVERIFIED, memory.state.value)
        assertNull(memory.verifiedProfileEmail.value)
        assertFalse(memory.suppressRefreshOnce.value)
    }

    @Test
    fun confirmedReplacementRemainsVerifiedWithoutRedundantRefresh() = runBlocking {
        val memory = YouTubeVerificationMemory()
        val newAccount = StreamingAccount("youtube", "new-channel", "New Channel", false)
        memory.connectAccount {
            memory.state.value = YouTubeAccountVerificationState.VERIFIED
            memory.verifiedProfileEmail.value = "viewer@example.com"
        }

        assertTrue(hasVerifiedYouTubeAccount(
            newAccount, memory.state.value, memory.verifiedProfileEmail.value, "viewer@example.com",
        ))
        assertTrue(memory.suppressRefreshOnce.value)
    }

    @Test
    fun linkedAccountCanChangeOnlyWhileIdle() {
        assertTrue(canChangeYouTubeAccount(true, BroadcastState.IDLE, false, false, false))
        for (state in BroadcastState.entries.filterNot { it == BroadcastState.IDLE }) {
            assertFalse(canChangeYouTubeAccount(true, state, false, false, false))
        }
        assertFalse(canChangeYouTubeAccount(true, BroadcastState.IDLE, true, false, false))
        assertFalse(canChangeYouTubeAccount(false, BroadcastState.IDLE, false, false, false))
        assertFalse(canChangeYouTubeAccount(true, BroadcastState.IDLE, false, true, false))
        assertFalse(canChangeYouTubeAccount(true, BroadcastState.IDLE, false, false, true))
    }

    @Test
    fun failedPreparationCannotChangeAccountUntilIdleIsConfirmed() {
        val unresolvedStates = listOf(
            BroadcastState.FAILED,
            BroadcastState.PREPARED,
            BroadcastState.CANCELLING_PREPARATION,
            BroadcastState.FAILED,
        )
        unresolvedStates.forEach { state ->
            assertFalse(canChangeYouTubeAccount(true, state, false, false, false))
        }
        assertTrue(canChangeYouTubeAccount(true, BroadcastState.IDLE, false, false, false))
    }

    @Test
    fun cancellingAuthorizationRestoresPreviousStatusAndEnablesRetryImmediately() {
        val result = cancelYouTubeAuthorization(
            accountStatusBeforeAuthorization = UiText.Dynamic("연결된 계정이 없습니다"),
            verificationState = YouTubeAccountVerificationState.VERIFIED,
        )

        assertEquals(UiText.Dynamic("연결된 계정이 없습니다"), result.accountStatus)
        assertEquals(YouTubeAccountVerificationState.VERIFIED, result.verificationState)
        assertFalse(result.isActionInProgress)
        assertFalse(result.shouldRefreshAccount)
    }

    @Test
    fun serverConfirmedConnectionStaysVerifiedWhenCacheSaveFails() {
        val account = StreamingAccount(
            provider = "youtube",
            channelId = "verified-channel",
            channelTitle = "Verified Channel",
            reconnectRequired = false,
        )
        var acceptedAccount: StreamingAccount? = null
        var verificationState = YouTubeAccountVerificationState.UNVERIFIED

        val cacheUpdated = acceptServerVerifiedYouTubeAccount(
            account = account,
            onVerified = { confirmedAccount ->
                acceptedAccount = confirmedAccount
                verificationState = YouTubeAccountVerificationState.VERIFIED
            },
            saveConnection = { throw IllegalStateException("cache commit failed") },
            removeConnection = {},
        )

        assertFalse(cacheUpdated)
        assertEquals(account, acceptedAccount)
        assertEquals(YouTubeAccountVerificationState.VERIFIED, verificationState)
    }

    @Test
    fun serverConfirmedDisconnectionStaysVerifiedWhenCacheRemovalFails() {
        val cachedAccount = StreamingAccount(
            provider = "youtube",
            channelId = "stale-channel",
            channelTitle = "Stale Channel",
            reconnectRequired = false,
        )
        var displayedAccount: StreamingAccount? = cachedAccount
        var verificationState = YouTubeAccountVerificationState.UNVERIFIED

        val cacheUpdated = acceptServerVerifiedYouTubeAccount(
            account = null,
            onVerified = { confirmedAccount ->
                displayedAccount = confirmedAccount
                verificationState = YouTubeAccountVerificationState.VERIFIED
            },
            saveConnection = {},
            removeConnection = { throw IllegalStateException("cache commit failed") },
        )

        assertFalse(cacheUpdated)
        assertEquals(null, displayedAccount)
        assertEquals(YouTubeAccountVerificationState.VERIFIED, verificationState)
    }
}
