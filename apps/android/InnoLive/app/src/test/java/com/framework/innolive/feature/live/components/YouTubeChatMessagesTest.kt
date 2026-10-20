package com.framework.innolive.feature.live.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `YouTubeChatMessageTests`와 같은 메시지 정리·메시지 수신 규칙입니다. */
class YouTubeChatMessagesTest {
    private val document = YouTubeViewerChatPolicy.url("dQw4w9WgXcQ")!!

    @Test fun snapshotKeepsRepeatedTextFromDifferentMessagesAndBoundsInput() {
        val messages = listOf(
            YouTubeChatMessage("1", "viewer", "hello"),
            YouTubeChatMessage("2", "viewer", "hello"),
            YouTubeChatMessage("2", "viewer", "duplicate"),
            YouTubeChatMessage("3", "viewer", "  \n "),
            YouTubeChatMessage("4", "a".repeat(200), "b".repeat(3_000)),
        )
        val visible = YouTubeChatSnapshot("", messages, ready = true).visibleMessages
        assertEquals(listOf("1", "2", "4"), visible.map { it.id })
        assertEquals(100, visible.last().author.length)
        assertEquals(2_000, visible.last().text.length)
        val many = (0 until 100).map { YouTubeChatMessage(it.toString(), "viewer", "hello") }
        assertEquals(50, YouTubeChatSnapshot("", many, ready = true).visibleMessages.size)
        assertEquals("50", YouTubeChatSnapshot("", many, ready = true).visibleMessages.first().id)
    }

    @Test fun snapshotTrimsTextDropsEmptyIdsAndBoundsIds() {
        val visible = YouTubeChatSnapshot("", listOf(
            YouTubeChatMessage("", "viewer", "no id"),
            YouTubeChatMessage("x".repeat(300), "viewer", "  padded  "),
        ), ready = true).visibleMessages
        assertEquals(1, visible.size)
        assertEquals(256, visible.single().id.length)
        assertEquals("padded", visible.single().text)
    }

    @Test fun boundsDoNotSplitEmoji() {
        val text = "😊".repeat(2_001)
        val visible = YouTubeChatSnapshot("", listOf(YouTubeChatMessage("1", "👍".repeat(101), text)), ready = true)
            .visibleMessages.single()
        assertEquals("😊".repeat(2_000), visible.text)
        assertEquals("👍".repeat(100), visible.author)
        assertEquals("A", "a".prefixCharacters(1).uppercase())
        assertEquals("", "".prefixCharacters(1))
    }

    @Test fun bridgeParsesScriptPayload() {
        val json = """{"url":"$document","messages":[{"id":"m1","author":"viewer","text":"안녕 😊"}],"ready":true}"""
        val snapshot = YouTubeChatBridge.parse(json)!!
        assertEquals(document, snapshot.url)
        assertTrue(snapshot.ready)
        assertEquals(listOf(YouTubeChatMessage("m1", "viewer", "안녕 😊")), snapshot.messages)
    }

    @Test fun bridgeRejectsMalformedOrOversizedPayload() {
        for (json in listOf(
            null, "", "not json", "[]", """{"url":"$document","messages":[]}""",
            """{"url":"$document","messages":[{"id":"1","author":"a"}],"ready":true}""",
            """{"url":"$document","messages":"x","ready":true}""",
        )) {
            assertNull(json, YouTubeChatBridge.parse(json))
        }
        val text = "가".repeat(YouTubeChatBridge.MAX_PAYLOAD_BYTES / 3)
        assertNull(YouTubeChatBridge.parse(
            """{"url":"$document","messages":[{"id":"1","author":"a","text":"$text"}],"ready":true}""",
        ))
        assertNull(YouTubeChatBridge.parse("a".repeat(YouTubeChatBridge.MAX_PAYLOAD_BYTES + 1)))
    }

    @Test fun bridgeAcceptsOnlyCurrentChatDocument() {
        fun payload(url: String) = """{"url":"$url","messages":[],"ready":true}"""
        assertTrue(YouTubeChatBridge.accept(payload(document), document)!!.ready)
        assertTrue(YouTubeChatBridge.accept(payload("https://www.youtube.com/live_chat?v=dQw4w9WgXcQ"), document) != null)
        for (url in listOf(
            YouTubeViewerChatPolicy.url("abcdefghijk")!!,
            "https://evil.example/live_chat?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "about:blank",
        )) {
            assertNull(url, YouTubeChatBridge.accept(payload(url), document))
        }
    }

    @Test fun stateReceivesOnlyValidCurrentDocumentMessages() {
        val state = YouTubeViewerChatState(document)
        state.receive(0, """{"url":"$document","messages":[{"id":"1","author":"viewer","text":"hi"}],"ready":true}""")
        assertEquals(listOf("1"), state.load.messages.map { it.id })
        state.receive(0, """{"url":"${YouTubeViewerChatPolicy.url("abcdefghijk")}","messages":[],"ready":true}""")
        assertEquals(1, state.load.messages.size)
        state.receive(0, "broken")
        assertEquals(1, state.load.messages.size)
        state.retry()
        assertTrue(state.load.messages.isEmpty())
        assertFalse(state.load.chatReady)
    }

    @Test fun scriptExtractsIosRendererTypesAndPostsToBridge() {
        val script = YouTubeChatBridge.SCRIPT
        for (selector in listOf(
            "yt-live-chat-text-message-renderer", "yt-live-chat-paid-message-renderer",
            "yt-live-chat-membership-item-renderer", "yt-live-chat-paid-sticker-renderer",
            "#author-name", "#message", "#header-subtext", "#sticker", "is-deleted", "yt-live-chat-renderer",
        )) {
            assertTrue(selector, selector in script)
        }
        assertTrue("window.${YouTubeChatBridge.NAME}.postMessage(value)" in script)
        assertTrue("setTimeout(publish, 100)" in script)
        assertTrue("\${index}:\${author}:\${body}" in script)
        assertFalse("webkit" in script)
    }

    @Test fun userAgentIsDesktopChromeWithDeviceVersion() {
        val agent = viewerChatUserAgent(
            "Mozilla/5.0 (Linux; Android 16; SM-S931N Build/BP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36",
        )
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Safari/537.36",
            agent,
        )
        for (token in listOf("Android", "Mobile", "; wv", "Version/")) assertFalse(token, token in agent)
        assertTrue("Chrome/" in viewerChatUserAgent(null))
    }
}
