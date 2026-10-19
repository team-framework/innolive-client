package com.framework.innolive.feature.live.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeViewerChatWebViewTest {
    @Test fun readinessTimeoutFailsOnlyAFinishedDocumentWithoutChatList() {
        val initial = ViewerChatLoadState()
        assertTrue(initial.loading)
        // iOS처럼 문서를 다 받은 뒤부터만 준비 시간 제한을 센다.
        assertEquals(initial, initial.readinessTimeout(0))
        val finished = initial.pageFinished(0)
        assertTrue(finished.awaitingReady)
        assertEquals(ViewerChatLoadState(loading = false, failed = true), finished.readinessTimeout(0))
        val ready = finished.received(0, YouTubeChatSnapshot("", emptyList(), ready = true))
        assertFalse(ready.awaitingReady)
        assertEquals(ready, ready.readinessTimeout(0))
        // 다시 시도 뒤 이전 시도의 타이머는 새 읽기를 실패로 바꾸지 않는다.
        val retried = finished.readinessTimeout(0).retry()
        assertEquals(ViewerChatLoadState(attempt = 1), retried)
        assertEquals(retried.pageFinished(1), retried.pageFinished(1).readinessTimeout(0))
        assertTrue(retried.pageFinished(1).readinessTimeout(1).failed)
    }

    @Test fun messagesFollowCurrentAttemptAndReadyClearsFailure() {
        val message = YouTubeChatMessage("1", "viewer", "hello")
        val snapshot = YouTubeChatSnapshot("", listOf(message, message.copy(text = " ")), ready = true)
        val received = ViewerChatLoadState().received(0, snapshot)
        assertEquals(ViewerChatLoadState(loading = false, messages = listOf(message), chatReady = true), received)
        // 지난 시도의 늦은 메시지는 무시한다.
        assertEquals(received.retry(), received.retry().received(0, snapshot))
        // 준비되지 않은 상태는 메시지만 바꾸고 실패 표시는 그대로 둔다.
        val failed = ViewerChatLoadState().pageFinished(0).fail(0)
        assertTrue(failed.received(0, snapshot.copy(ready = false)).failed)
        assertFalse(failed.received(0, snapshot).failed)
        // 새 문서를 읽기 시작하면 이전 메시지와 준비 상태를 비운다.
        assertEquals(ViewerChatLoadState(), received.copy(loading = false).pageStarted(0))
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
        assertEquals(setOf("YSC", "VISITOR_INFO1_LIVE", "__Secure-YEC", "LIVE_CHAT_PATH", "SOCS") +
            YouTubeViewerChatWebData.knownCookieNames, expiredNames)
        assertTrue(store.setCookies.all { (url, value) ->
            url in YouTubeViewerChatWebData.cookieUrls && "Max-Age=0" in value && "Secure" in value
        })
        assertTrue(store.setCookies.contains("https://www.youtube.com/" to
            "YSC=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=.youtube.com"))
        assertTrue(store.setCookies.contains("https://www.youtube.com/live_chat" to
            "LIVE_CHAT_PATH=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/live_chat; Secure"))
        assertTrue(store.lookedUp.none { "naver" in it || "chzzk" in it || "innolive" in it })
        assertEquals(YouTubeViewerChatWebData.storageOrigins, store.deletedOrigins)
        assertTrue(store.deletedOrigins.all { it.startsWith("https://") && it.endsWith("youtube.com") })
    }

    // S25 실기기: YouTube 방문자 쿠키는 top_frame_site_key=https://youtube.com 파티션에 저장되어
    // Partitioned 없는 만료 쿠키로는 지워지지 않았다(__Secure-BUCKET 하나만 삭제).
    @Test fun clearingAlsoExpiresPartitionedVisitorCookiesOnYouTubeDomain() {
        val store = RecordingStore(emptyMap())
        YouTubeViewerChatWebData.clear(store)

        for (name in listOf("YSC", "VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "__Secure-ROLLOUT_TOKEN",
            "__Secure-YNID", "__Secure-BUCKET")) {
            assertTrue(name, store.setCookies.contains("https://www.youtube.com/" to
                "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=.youtube.com; " +
                "SameSite=None; Partitioned"))
            assertTrue(name, store.setCookies.contains("https://www.youtube.com/" to
                "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=.youtube.com"))
        }
        assertTrue(store.setCookies.all { (_, value) -> "youtube.com" in value || "Domain=" !in value })
    }

    @Test fun flushRunsOnlyAfterEveryExpiryCallbackAndReportsCounts() {
        val store = RecordingStore(mapOf("https://www.youtube.com/" to "YSC=a"), deferResults = true)
        var result: YouTubeViewerChatWebData.ClearResult? = null
        YouTubeViewerChatWebData.clear(store) { result = it }

        assertEquals(0, store.flushes)
        assertNull(result)
        // 마지막 콜백 전에는 flush하지 않는다.
        store.completeAll(dropLast = 1, ok = false)
        assertEquals(0, store.flushes)
        store.cookies.clear()
        store.completeAll(ok = true)
        assertEquals(1, store.flushes)
        assertEquals(YouTubeViewerChatWebData.ClearResult(store.setCookies.size, store.setCookies.size - 1, emptySet()), result)
    }

    @Test fun remainingCookiesAreReportedByName() {
        val store = RecordingStore(mapOf("https://youtube.com/" to "YSC=a; PREF=b"))
        var result: YouTubeViewerChatWebData.ClearResult? = null
        YouTubeViewerChatWebData.clear(store) { result = it }
        assertEquals(1, store.flushes)
        assertEquals(setOf("YSC", "PREF"), result!!.remaining)
        assertEquals(0, result!!.failed)
    }

    @Test fun cookieHeaderParsingIgnoresEmptyParts() {
        assertEquals(setOf("a", "b"), YouTubeViewerChatWebData.cookieNames(" a=1; ; b=2=3;"))
        assertEquals(emptySet<String>(), YouTubeViewerChatWebData.cookieNames(null))
    }

    private class RecordingStore(
        cookies: Map<String, String>,
        private val deferResults: Boolean = false,
    ) : YouTubeViewerChatWebData.Store {
        val cookies = cookies.toMutableMap()
        val lookedUp = mutableListOf<String>()
        val setCookies = mutableListOf<Pair<String, String>>()
        val deletedOrigins = mutableListOf<String>()
        private val pending = ArrayDeque<(Boolean) -> Unit>()
        var flushes = 0

        override fun cookies(url: String): String? {
            lookedUp += url
            return cookies[url]
        }
        override fun setCookie(url: String, value: String, onResult: (Boolean) -> Unit) {
            setCookies += url to value
            if (deferResults) pending += onResult else onResult(true)
        }
        override fun flushCookies() { flushes++ }
        override fun deleteStorage(origin: String) { deletedOrigins += origin }

        fun completeAll(ok: Boolean, dropLast: Int = 0) {
            repeat(pending.size - dropLast) { pending.removeFirst()(ok) }
        }
    }
}
