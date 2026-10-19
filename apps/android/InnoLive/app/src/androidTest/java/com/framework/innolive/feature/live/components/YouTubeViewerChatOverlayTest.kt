package com.framework.innolive.feature.live.components

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class YouTubeViewerChatOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    @Test
    fun overlayAppearsOnlyWhileLiveChatUrlExistsAndNewVideoStartsEmpty() {
        val first = YouTubeViewerChatPolicy.url("dQw4w9WgXcQ")
        val next = YouTubeViewerChatPolicy.url("abcdefghijk")
        var url by mutableStateOf<String?>(null)
        var current: YouTubeViewerChatState? = null
        compose.setContent {
            val chat = rememberYouTubeViewerChatState(url)
            current = chat
            if (chat != null) YouTubeViewerChatOverlay(chat, hidden = false, onToggleHidden = {}, compact = false)
        }
        compose.onNodeWithTag("youtube-viewer-chat-overlay").assertDoesNotExist()

        compose.runOnIdle { url = first }
        compose.onNodeWithTag("youtube-viewer-chat-overlay").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.viewer_chat_connecting)).assertIsDisplayed()
        val firstState = compose.runOnIdle { assertNotNull(current); current!! }
        compose.runOnIdle {
            firstState.load = ViewerChatLoadState(
                loading = false, chatReady = true, messages = listOf(YouTubeChatMessage("1", "viewer", "hello")),
            )
        }
        compose.onNodeWithTag("youtube-chat-message-1").assertIsDisplayed()

        // 동영상이 바뀌면 새 문서를 받고 이전 메시지는 남기지 않는다.
        compose.runOnIdle { url = next }
        compose.runOnIdle { assertNotSame(firstState, current) }
        compose.onNodeWithTag("youtube-chat-message-1").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.viewer_chat_connecting)).assertIsDisplayed()

        compose.runOnIdle { url = null }
        compose.onNodeWithTag("youtube-viewer-chat-overlay").assertDoesNotExist()
    }

    @Test
    fun messagesRenderAndMenuHidesAndShowsTheList() {
        val state = YouTubeViewerChatState(YouTubeViewerChatPolicy.url("dQw4w9WgXcQ")!!)
        state.load = ViewerChatLoadState(
            loading = false,
            chatReady = true,
            messages = listOf(YouTubeChatMessage("1", "@viewer", "안녕하세요"), YouTubeChatMessage("2", "fan", "반가워요")),
        )
        var hidden by mutableStateOf(false)
        compose.setContent {
            YouTubeViewerChatOverlay(state, hidden = hidden, onToggleHidden = { hidden = !hidden }, compact = false)
        }
        compose.onNodeWithTag("youtube-chat-message-1").assertIsDisplayed()
        compose.onNodeWithTag("youtube-chat-message-2").assertIsDisplayed()
        compose.onNodeWithText("반가워요", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.viewer_chat_menu)).performClick()
        compose.onNodeWithText(string(R.string.viewer_chat_hide)).performClick()
        compose.runOnIdle { assertTrue(hidden) }
        compose.onNodeWithTag("youtube-chat-messages").assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.viewer_chat_menu)).assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.viewer_chat_menu)).performClick()
        compose.onNodeWithText(string(R.string.viewer_chat_show)).performClick()
        compose.onNodeWithTag("youtube-chat-message-2").assertIsDisplayed()
    }

    @Test
    fun emptyAndFailedChatShowIosNoticesAndRetry() {
        val state = YouTubeViewerChatState(YouTubeViewerChatPolicy.url("dQw4w9WgXcQ")!!)
        compose.setContent {
            YouTubeViewerChatOverlay(state, hidden = false, onToggleHidden = {}, compact = true)
        }
        compose.onNodeWithText(string(R.string.viewer_chat_connecting)).assertIsDisplayed()

        compose.runOnIdle { state.load = ViewerChatLoadState(loading = false, chatReady = true) }
        compose.onNodeWithText(string(R.string.viewer_chat_waiting)).assertIsDisplayed()

        compose.runOnIdle { state.load = state.load.fail(0) }
        compose.onNodeWithText(string(R.string.viewer_chat_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_retry)).performClick()
        compose.runOnIdle { assertEquals(ViewerChatLoadState(attempt = 1), state.load) }
    }

    /** iOS `testDOMBridgeExtractsBodyEmojiUpdatesAndDeletions`처럼 실제 추출 스크립트와 메시지 통로를 고정 문서로 확인한다. */
    @Test
    fun bridgeExtractsBodyEmojiUpdatesAndDeletionsFromChatDocument() {
        val document = YouTubeViewerChatPolicy.url("dQw4w9WgXcQ")!!
        val state = YouTubeViewerChatState(document)
        var webView: WebView? = null
        compose.setContent {
            AndroidView(
                modifier = Modifier.size(340.dp, 240.dp),
                factory = { context ->
                    createViewerChatWebView(
                        context, document,
                        onStarted = { state.load = state.load.pageStarted(0) },
                        onFinished = { state.load = state.load.pageFinished(0) },
                        onFailed = { state.load = state.load.fail(0) },
                        onMessage = { state.receive(0, it) },
                        load = { loadDataWithBaseURL(document, FIXTURE_HTML, "text/html", "utf-8", document) },
                    ).also { webView = it }
                },
                onRelease = { it.destroy() },
            )
        }
        compose.waitUntil(15_000) { state.load.messages.size == 1 }
        compose.runOnIdle {
            assertTrue(state.load.chatReady)
            assertEquals("viewer", state.load.messages.single().author)
            assertEquals("안녕하세요 😊 긴 댓글입니다", state.load.messages.single().text)
        }

        compose.runOnIdle { webView!!.evaluateJavascript("document.querySelector('#message').textContent = '수정된 댓글'", null) }
        compose.waitUntil(5_000) { state.load.messages.firstOrNull()?.text == "수정된 댓글" }
        compose.runOnIdle {
            webView!!.evaluateJavascript(
                "document.querySelector('yt-live-chat-text-message-renderer').setAttribute('is-deleted', '')", null,
            )
        }
        compose.waitUntil(5_000) { state.load.messages.isEmpty() }
        compose.runOnIdle {
            webView!!.evaluateJavascript(
                "document.querySelector('yt-live-chat-text-message-renderer').removeAttribute('is-deleted')", null,
            )
        }
        compose.waitUntil(5_000) { state.load.messages.size == 1 }
    }

    private companion object {
        val FIXTURE_HTML = """
            <html><body><yt-live-chat-renderer>
            <yt-live-chat-text-message-renderer id="message-1">
            <span id="author-name">viewer</span>
            <span id="message">안녕하세요 <img alt="😊"> 긴 댓글입니다</span>
            </yt-live-chat-text-message-renderer>
            </yt-live-chat-renderer></body></html>
        """.trimIndent()
    }
}
