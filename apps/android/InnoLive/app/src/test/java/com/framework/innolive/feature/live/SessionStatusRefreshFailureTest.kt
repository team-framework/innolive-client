package com.framework.innolive.feature.live

import com.framework.innolive.feature.login.oauth.google.InvalidRefreshTokenException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SessionStatusRefreshFailureTest {
    @Test fun unrecoverableRefreshFailureEndsConnection() {
        assertTrue(sessionStatusRefreshEndsConnection(InvalidRefreshTokenException(401)))
        // 로그아웃 뒤 세션이 없거나 저장소가 닫히면 매 주기 401만 반복하므로 방송을 끝냅니다.
        assertTrue(sessionStatusRefreshEndsConnection(IllegalStateException("Authentication session is missing.")))
    }

    @Test fun transientRefreshFailureKeepsConnection() {
        // 모바일망 순간 끊김과 갱신 API 5xx는 다음 상태 조회 주기에 다시 시도합니다.
        assertFalse(sessionStatusRefreshEndsConnection(IOException("timeout")))
        assertFalse(sessionStatusRefreshEndsConnection(IOException("Authentication refresh failed with HTTP 503.")))
    }
}
