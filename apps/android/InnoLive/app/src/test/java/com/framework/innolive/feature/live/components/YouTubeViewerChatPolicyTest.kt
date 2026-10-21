package com.framework.innolive.feature.live.components

import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.SessionSnapshot
import com.framework.innolive.feature.live.SessionTarget
import com.framework.innolive.feature.live.parseSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeViewerChatPolicyTest {
    private val chat = "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ"

    private fun snapshot(provider: String, status: String?, phase: String?, videoId: String? = "dQw4w9WgXcQ") =
        SessionSnapshot("s", targets = listOf(SessionTarget(provider, status, phase, null, null, videoId)))

    @Test fun onlyElevenCharacterVideoIdsProduceAChatUrl() {
        assertEquals(chat, YouTubeViewerChatPolicy.url("dQw4w9WgXcQ"))
        for (invalid in listOf(null, "", "short", "dQw4w9WgXcQ1", "dQw4w9WgX Q", "dQw4w9WgX/Q", "dQw4w9WgX?Q")) {
            assertNull(invalid, YouTubeViewerChatPolicy.url(invalid))
        }
    }

    @Test fun chatIsAvailableOnlyForStartedYouTubeTargets() {
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "streaming", "live")))
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "paused", "live")))
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "reconnecting", "live")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(null))
        assertNull(YouTubeViewerChatPolicy.activeUrl(SessionSnapshot("s")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "streaming", "prepared")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "streaming", "going_live")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "streaming", null)))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "stopped", "live")))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", "streaming", "live", videoId = null)))
        assertNull(YouTubeViewerChatPolicy.activeUrl(snapshot("chzzk", "streaming", "live")))
    }

    @Test fun privateAndMadeForKidsBroadcastsDoNotLoadChat() {
        val live = snapshot("youtube", "streaming", "live")
        fun settings(privacy: String, madeForKids: Boolean?) = BroadcastSettings("t", "", privacy, madeForKids, "22")
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(live, settings("public", false)))
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(live, settings("unlisted", false)))
        assertNull(YouTubeViewerChatPolicy.activeUrl(live, settings("private", false)))
        assertNull(YouTubeViewerChatPolicy.activeUrl(live, settings("public", true)))
        assertNull(YouTubeViewerChatPolicy.activeUrl(live, settings("unlisted", true)))
        // 준비 설정을 모르는 세션(앱을 다시 열어 이어 받은 방송)은 이전처럼 채팅을 읽어 본다.
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(live, null))
    }

    // iOS testDirectLiveIdleStatusAndOtherTargetStopKeepChat: 직접 송출은 라이브 중에도 status가 idle이다.
    @Test fun directPublishLiveIdleAndOtherLiveStatusesKeepChat() {
        for (status in listOf("idle", "paused_reconnecting", "paused_reconfiguring", "reconfiguring", "future_status", null)) {
            assertEquals(status.toString(), chat, YouTubeViewerChatPolicy.activeUrl(snapshot("youtube", status, "live")))
        }
        val direct = parseSessionSnapshot(
            """{"targets":[
                {"provider":"chzzk","stream":{"status":"stopped","broadcast_phase":"live"}},
                {"provider":"youtube","video_id":"dQw4w9WgXcQ","stream":{"status":"idle","broadcast_phase":"live"}}
            ]}""",
            "s",
        )
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(direct))
    }

    @Test fun stoppedYouTubeHidesChatAndNewVideoIdChangesTheDocument() {
        val live = parseSessionSnapshot(
            """{"targets":[{"provider":"youtube","video_id":"dQw4w9WgXcQ","stream":{"status":"streaming","broadcast_phase":"live"}}]}""",
            "s",
        )
        assertEquals(chat, YouTubeViewerChatPolicy.activeUrl(live))
        // 해상도·방식 변경으로 서버가 새 동영상 ID를 주면 이전 값을 덮어쓰고 다른 채팅 문서가 된다.
        val switched = parseSessionSnapshot(
            """{"targets":[{"provider":"youtube","video_id":"abcdefghijk","stream":{"status":"streaming","broadcast_phase":"live"}}]}""",
            "s",
            live,
        )
        assertEquals("abcdefghijk", switched.targets!!.single().videoId)
        assertEquals("https://www.youtube.com/live_chat?is_popout=1&v=abcdefghijk", YouTubeViewerChatPolicy.activeUrl(switched))
        val stopped = parseSessionSnapshot("""{"stream":{"status":"stopped","broadcast_phase":"live"}}""", "s", switched)
        assertNull(YouTubeViewerChatPolicy.activeUrl(stopped))
        val removed = parseSessionSnapshot("""{"targets":[]}""", "s", switched)
        assertNull(YouTubeViewerChatPolicy.activeUrl(removed))
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

    @Test fun userLinksSubframesAndBlockedRedirectsFollowIosNavigationRules() {
        fun decide(
            url: String,
            mainFrame: Boolean = true,
            gesture: Boolean = false,
            redirect: Boolean = false,
            loaded: Boolean = true,
        ) = YouTubeViewerChatPolicy.navigationDecision(url, chat, mainFrame, gesture, redirect, loaded)

        assertEquals(ViewerChatNavigation.ALLOW, decide(chat))
        assertEquals(ViewerChatNavigation.ALLOW, decide(chat, loaded = false))
        // 같은 문서라도 사용자가 누른 링크는 iOS linkActivated처럼 막는다.
        assertEquals(ViewerChatNavigation.BLOCK, decide(chat, gesture = true))
        assertEquals(ViewerChatNavigation.BLOCK, decide(chat, mainFrame = false))
        assertEquals(ViewerChatNavigation.BLOCK, decide("https://accounts.google.com/signin", gesture = true))
        // 문서를 읽는 중의 동의 화면 리디렉션은 30초를 기다리지 않고 실패 화면으로 바꾼다.
        val consent = "https://consent.youtube.com/m?continue=https%3A%2F%2Fwww.youtube.com%2Flive_chat"
        assertEquals(ViewerChatNavigation.BLOCK_AND_FAIL, decide(consent, redirect = true))
        assertEquals(ViewerChatNavigation.BLOCK_AND_FAIL, decide(consent, loaded = false))
        assertEquals(ViewerChatNavigation.BLOCK, decide(consent, mainFrame = false, redirect = true, loaded = false))
    }

    @Test fun mainFrameFormSubmissionsAreBlockedButChatRequestsAreNot() {
        assertTrue(YouTubeViewerChatPolicy.blocksMainFrameRequest("POST", isForMainFrame = true))
        assertTrue(YouTubeViewerChatPolicy.blocksMainFrameRequest("put", isForMainFrame = true))
        assertTrue(YouTubeViewerChatPolicy.blocksMainFrameRequest(null, isForMainFrame = true))
        assertFalse(YouTubeViewerChatPolicy.blocksMainFrameRequest("GET", isForMainFrame = true))
        // 채팅 메시지는 하위 리소스 POST로 받아 오므로 막지 않는다.
        assertFalse(YouTubeViewerChatPolicy.blocksMainFrameRequest("POST", isForMainFrame = false))
    }
}
