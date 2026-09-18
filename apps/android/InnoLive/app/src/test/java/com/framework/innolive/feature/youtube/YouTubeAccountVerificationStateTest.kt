package com.framework.innolive.feature.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class YouTubeAccountVerificationStateTest {
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
