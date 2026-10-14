package com.framework.innolive.feature.youtube

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** plan-usage-v1.md와 streaming-accounts 요청의 공통 401 처리: 기존 인증 갱신 뒤 같은 요청을 한 번만 재시도한다. */
class YouTubeUnauthorizedRetryTest {
    @Test fun retriesOnceWithTheRefreshedTokenAfter401() = runBlocking {
        val tokens = mutableListOf<String>()
        var refreshes = 0

        val result = retryYouTubeUnauthorized("old-token", { refreshes += 1; "new-token" }) { token ->
            tokens += token
            if (token == "old-token") throw YouTubeApiException(401, "plan")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("old-token", "new-token"), tokens)
        assertEquals(1, refreshes)
    }

    @Test fun secondUnauthorizedIsThrownAfterASingleRefresh() {
        var refreshes = 0
        var calls = 0

        val exception = assertThrows(YouTubeApiException::class.java) {
            runBlocking {
                retryYouTubeUnauthorized("old-token", { refreshes += 1; "new-token" }) {
                    calls += 1
                    throw YouTubeApiException(401, "plan")
                }
            }
        }

        assertEquals(401, exception.statusCode)
        assertEquals(2, calls)
        assertEquals(1, refreshes)
    }

    @Test fun otherFailuresAndRefreshFailuresDoNotRetryTheRequest() {
        var refreshes = 0
        var calls = 0

        val forbidden = assertThrows(YouTubeApiException::class.java) {
            runBlocking {
                retryYouTubeUnauthorized("token", { refreshes += 1; "new-token" }) {
                    calls += 1
                    throw YouTubeApiException(403, "plan")
                }
            }
        }
        assertEquals(403, forbidden.statusCode)
        assertEquals(0, refreshes)
        assertEquals(1, calls)

        val refreshFailure = assertThrows(IOException::class.java) {
            runBlocking {
                retryYouTubeUnauthorized("token", { throw IOException("refresh unavailable") }) {
                    calls += 1
                    throw YouTubeApiException(401, "plan")
                }
            }
        }
        assertEquals("refresh unavailable", refreshFailure.message)
        assertEquals(2, calls)
    }

    @Test fun youtubeAccountIsFoundCaseInsensitivelyAmongOtherProviders() {
        val chzzk = StreamingAccount("chzzk", "c", "chzzk channel", false)
        val youtube = StreamingAccount("YouTube", "y", "youtube channel", true)

        assertEquals(youtube, findYouTubeAccount(listOf(chzzk, youtube)))
        assertNull(findYouTubeAccount(listOf(chzzk)))
        assertNull(findYouTubeAccount(emptyList()))
    }
}
