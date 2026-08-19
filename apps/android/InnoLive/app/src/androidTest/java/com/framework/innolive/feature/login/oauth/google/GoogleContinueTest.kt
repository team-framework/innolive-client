package com.framework.innolive.feature.login.oauth.google

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleContinueTest {
    @Test
    fun parseGoogleSessionParsesValidResponse() {
        val session = parseGoogleSession(
            """
                {
                  "access_token": "access-token",
                  "refresh_token": "refresh-token",
                  "token_type": "Bearer",
                  "expires_in": 3600,
                  "refresh_expires_in": 86400
                }
            """.trimIndent(),
        )

        assertEquals("access-token", session.accessToken)
        assertEquals("refresh-token", session.refreshToken)
        assertEquals("Bearer", session.tokenType)
        assertEquals(3600L, session.expiresIn)
        assertEquals(86400L, session.refreshExpiresIn)
    }

    @Test
    fun parseGoogleSessionRejectsZeroExpiry() {
        assertThrows(IllegalStateException::class.java) {
            parseGoogleSession(
                """
                    {
                      "access_token": "access-token",
                      "refresh_token": "refresh-token",
                      "token_type": "Bearer",
                      "expires_in": 0,
                      "refresh_expires_in": 86400
                    }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun parseGoogleSessionRejectsMissingExpiry() {
        assertThrows(IllegalStateException::class.java) {
            parseGoogleSession(
                """
                    {
                      "access_token": "access-token",
                      "refresh_token": "refresh-token",
                      "token_type": "Bearer",
                      "expires_in": 3600
                    }
                """.trimIndent(),
            )
        }
    }
}
