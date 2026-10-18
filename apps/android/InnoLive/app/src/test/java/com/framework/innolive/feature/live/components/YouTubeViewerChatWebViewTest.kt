package com.framework.innolive.feature.live.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeViewerChatWebViewTest {
    @Test fun timeoutFailsOnlyAnUnfinishedCurrentAttempt() {
        val initial = ViewerChatLoadState()
        assertTrue(initial.loading)
        assertEquals(ViewerChatLoadState(loading = false, failed = true), initial.timeout(0))
        assertEquals(ViewerChatLoadState(loading = false), initial.pageFinished(0).timeout(0))
        // 다시 시도 뒤 이전 시도의 타이머는 새 읽기를 실패로 바꾸지 않는다.
        val retried = initial.timeout(0).retry()
        assertEquals(ViewerChatLoadState(attempt = 1), retried)
        assertEquals(retried, retried.timeout(0))
        assertEquals(ViewerChatLoadState(attempt = 1, loading = false, failed = true), retried.timeout(1))
    }

    @Test fun staleAndLateCallbacksDoNotReviveAFailedLoad() {
        val failed = ViewerChatLoadState().fail(0)
        assertEquals(failed, failed.pageStarted(0))
        assertEquals(failed, failed.pageFinished(0))
        val retried = failed.retry()
        assertEquals(retried, retried.fail(0))
        assertEquals(retried, retried.pageFinished(0))
        assertFalse(retried.pageFinished(1).loading)
        assertTrue(retried.pageFinished(1).pageStarted(1).loading)
    }

    @Test fun releaseDetachesStopsBlanksThenDestroys() {
        val calls = mutableListOf<String>()
        releaseViewerChat(object : ViewerChatWebSurface {
            override fun detachClient() { calls += "detach" }
            override fun stopLoading() { calls += "stop" }
            override fun loadBlank() { calls += "blank" }
            override fun destroy() { calls += "destroy" }
        })
        assertEquals(listOf("detach", "stop", "blank", "destroy"), calls)
    }

    @Test fun clearingExpiresOnlyYouTubeCookiesAndDeletesYouTubeStorage() {
        val store = RecordingStore(mapOf(
            "https://www.youtube.com/" to "YSC=a; VISITOR_INFO1_LIVE=b; __Secure-YEC=c",
            "https://www.youtube.com/live_chat" to "YSC=a; LIVE_CHAT_PATH=d",
            "https://consent.youtube.com/" to "SOCS=e",
        ))
        YouTubeViewerChatWebData.clear(store)

        val expiredNames = store.setCookies.map { (_, value) -> value.substringBefore('=') }.toSet()
        assertEquals(setOf("YSC", "VISITOR_INFO1_LIVE", "__Secure-YEC", "LIVE_CHAT_PATH", "SOCS"), expiredNames)
        assertTrue(store.setCookies.all { (url, value) ->
            url in YouTubeViewerChatWebData.cookieUrls && "Max-Age=0" in value && "Secure" in value
        })
        assertTrue(store.setCookies.contains("https://www.youtube.com/" to
            "YSC=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=.youtube.com"))
        assertTrue(store.setCookies.contains("https://www.youtube.com/live_chat" to
            "LIVE_CHAT_PATH=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/live_chat; Secure"))
        assertTrue(store.lookedUp.none { "naver" in it || "chzzk" in it || "innolive" in it })
        assertEquals(1, store.flushes)
        assertEquals(YouTubeViewerChatWebData.storageOrigins, store.deletedOrigins)
        assertTrue(store.deletedOrigins.all { it.startsWith("https://") && it.endsWith("youtube.com") })
    }

    @Test fun cookieHeaderParsingIgnoresEmptyParts() {
        assertEquals(setOf("a", "b"), YouTubeViewerChatWebData.cookieNames(" a=1; ; b=2=3;"))
        assertEquals(emptySet<String>(), YouTubeViewerChatWebData.cookieNames(null))
    }

    private class RecordingStore(private val cookies: Map<String, String>) : YouTubeViewerChatWebData.Store {
        val lookedUp = mutableListOf<String>()
        val setCookies = mutableListOf<Pair<String, String>>()
        val deletedOrigins = mutableListOf<String>()
        var flushes = 0

        override fun cookies(url: String): String? {
            lookedUp += url
            return cookies[url]
        }
        override fun setCookie(url: String, value: String) { setCookies += url to value }
        override fun flushCookies() { flushes++ }
        override fun deleteStorage(origin: String) { deletedOrigins += origin }
    }
}
