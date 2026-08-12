package com.example.innolive.feature.login.oauth.google

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleSessionRefreshTest {
    @Test
    fun mergeRefreshedSessionRotatesTokensAndKeepsProfile() {
        val currentSession = GoogleSessionStore.Session(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            tokenType = "Bearer",
            expiresIn = 1,
            refreshExpiresIn = 1,
            profileName = "InnoLive User",
            profileEmail = "user@example.com",
        )

        assertEquals(
            GoogleSessionStore.Session(
                accessToken = "new-access-token",
                refreshToken = "new-refresh-token",
                tokenType = "Bearer",
                expiresIn = 900,
                refreshExpiresIn = 86_400,
                profileName = "InnoLive User",
                profileEmail = "user@example.com",
            ),
            mergeRefreshedSession(
                responseBody =
                    """
                    {
                      "access_token": "new-access-token",
                      "refresh_token": "new-refresh-token",
                      "token_type": "Bearer",
                      "expires_in": 900,
                      "refresh_expires_in": 86400
                    }
                    """.trimIndent(),
                currentSession = currentSession,
            ),
        )
    }
}
