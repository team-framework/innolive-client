package com.framework.innolive.feature.login.oauth.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSessionRefreshTest {
    /** account-deletion-v1.md: 무효 refresh token만 세션 만료 동작을 따르고, 일시 실패는 로그인을 유지한다. */
    @Test
    fun onlyTokenRejectionStatusesExpireTheSession() {
        for (status in listOf(400, 401, 403)) assertTrue("$status", isInvalidRefreshTokenStatus(status))
        for (status in listOf(404, 408, 429, 500, 502, 503)) assertFalse("$status", isInvalidRefreshTokenStatus(status))
        assertEquals(401, InvalidRefreshTokenException(401).statusCode)
    }

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
