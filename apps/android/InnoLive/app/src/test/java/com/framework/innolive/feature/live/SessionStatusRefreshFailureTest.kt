package com.framework.innolive.feature.live

import com.framework.innolive.feature.login.oauth.google.InvalidRefreshTokenException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SessionStatusRefreshFailureTest {
    @Test fun rejectedRefreshTokenEndsConnection() {
        assertTrue(sessionStatusRefreshEndsConnection(InvalidRefreshTokenException(401)))
    }

    @Test fun transientRefreshFailureKeepsConnection() {
        // 모바일망 순간 끊김과 갱신 API 5xx는 다음 상태 조회 주기에 다시 시도합니다.
        assertFalse(sessionStatusRefreshEndsConnection(IOException("timeout")))
        assertFalse(sessionStatusRefreshEndsConnection(IOException("Authentication refresh failed with HTTP 503.")))
    }
}
