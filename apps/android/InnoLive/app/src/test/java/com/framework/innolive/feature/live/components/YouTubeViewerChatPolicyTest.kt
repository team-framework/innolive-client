package com.framework.innolive.feature.live.components

import com.framework.innolive.feature.live.SessionSnapshot
import com.framework.innolive.feature.live.SessionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeViewerChatPolicyTest {
    private val chat = "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ"

    @Test fun onlyElevenCharacterVideoIdsProduceAChatUrl() {
        assertEquals(chat, YouTubeViewerChatPolicy.url("dQw4w9WgXcQ"))
        for (invalid in listOf(null, "", "short", "dQw4w9WgXcQ1", "dQw4w9WgX Q", "dQw4w9WgX/Q", "dQw4w9WgX?Q")) {
            assertNull(invalid, YouTubeViewerChatPolicy.url(invalid))
        }
    }

    @Test fun chatIsAvailableOnlyForStartedYouTubeTargets() {
        fun snapshot(provider: String, status: String?, phase: String?, videoId: String? = "dQw4w9WgXcQ") =
            SessionSnapshot("s", targets = listOf(SessionTarget(provider, status, phase, null, null, videoId)))

        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "streaming", "live")))
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "paused", "live")))
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "reconnecting", "live")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(null))
        assertNull(YouTubeViewerChatPolicy.activeUrl(SessionSnapshot("s")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "streaming", "prepared")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "stopped", "live")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "streaming", "live", videoId = null)))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("chzzk", "streaming", "live")))
    }

    @Test fun simulcastUsesTheYouTubeTargetOnly() {
        val snapshot = SessionSnapshot("s", targets = listOf(
            SessionTarget("chzzk", "streaming", "live", null, null),
            SessionTarget("youtube", "streaming", "live", null, null, "dQw4w9WgXcQ"),
        ))
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(snapshot))
        val youtubeStopped = SessionSnapshot("s", targets = listOf(
            SessionTarget("chzzk", "streaming", "live", null, null),
            SessionTarget("youtube", "stopped", "live", null, null, "dQw4w9WgXcQ"),
        ))
        assertNull(YouTubeViewerChatPolicy.activeUrl(youtubeStopped))
    }

    @Test fun navigationStaysOnTheSameLiveChatDocument() {
        assertTrue(YouTubeViewerChatPolicy.allowsNavigation(chat, chat))
        assertTrue(YouTubeViewerChatPolicy.allowsNavigation(
            "https://www.youtube.com/live_chat?v=dQw4w9WgXcQ&is_popout=1&dark_theme=1", chat))
        for (blocked in listOf(
            null,
            "http://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ",
            "https://youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ",
            "https://www.youtube.com:8443/live_chat?is_popout=1&v=dQw4w9WgXcQ",
            "https://user@www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/live_chat?is_popout=1&v=otherVideo1",
            "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ&v=otherVideo1",
            "https://www.youtube.com/live_chat?is_popout=1",
            "https://accounts.google.com/signin",
            "intent://www.youtube.com/live_chat#Intent;end",
            "not a url",
        )) {
            assertFalse(blocked.toString(), YouTubeViewerChatPolicy.allowsNavigation(blocked, chat))
        }
    }
}
