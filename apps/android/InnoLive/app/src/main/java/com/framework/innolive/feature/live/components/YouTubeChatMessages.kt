package com.framework.innolive.feature.live.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.BreakIterator

/** YouTube 채팅 문서에서 꺼낸 메시지 한 줄입니다. iOS `YouTubeChatMessage`와 같습니다. */
internal data class YouTubeChatMessage(val id: String, val author: String, val text: String)

/** 채팅 문서가 보낸 한 번의 상태입니다. [ready]는 `yt-live-chat-renderer`가 문서에 있는지입니다. */
internal data class YouTubeChatSnapshot(
    val url: String,
    val messages: List<YouTubeChatMessage>,
    val ready: Boolean,
) {
    /** iOS `visibleMessages`와 같이 최근 50개에서 빈 ID·중복 ID·빈 본문을 빼고 길이를 자릅니다. */
    val visibleMessages: List<YouTubeChatMessage>
        get() {
            val seen = HashSet<String>()
            return messages.takeLast(MAX_MESSAGES).mapNotNull { message ->
                val id = message.id.prefixCharacters(MAX_ID)
                if (id.isEmpty() || !seen.add(id)) return@mapNotNull null
                val text = message.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                YouTubeChatMessage(
                    id = id,
                    author = message.author.prefixCharacters(MAX_AUTHOR),
                    text = text.prefixCharacters(MAX_TEXT),
                )
            }
        }

    companion object {
        const val MAX_MESSAGES = 50
        const val MAX_ID = 256
        const val MAX_AUTHOR = 100
        const val MAX_TEXT = 2_000
    }
}

/** Swift `String.prefix`처럼 이모지·결합 문자를 자르지 않도록 글자(grapheme) 단위로 앞부분을 남깁니다. */
internal fun String.prefixCharacters(count: Int): String {
    if (length <= count) return this
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(this)
    var end = 0
    repeat(count) {
        val next = iterator.next()
        if (next == BreakIterator.DONE) return this
        end = next
    }
    return substring(0, end)
}

/**
 * 보이지 않는 채팅 WebView에서 메시지를 꺼내 네이티브 행으로 넘기는 다리입니다. 페이지 HTML은 앱 화면에 그리지 않습니다.
 * YouTube가 소유한 DOM 선택자에 의존하므로 YouTube 페이지가 바뀌면 실제 방송에서 다시 확인해야 합니다.
 */
internal object YouTubeChatBridge {
    const val NAME = "viewerChatMessages"
    const val ORIGIN = "https://www.youtube.com"
    /** iOS와 같은 메시지 크기 상한(UTF-8 바이트)입니다. */
    const val MAX_PAYLOAD_BYTES = 512_000

    /**
     * iOS `YouTubeChatBridge.script`와 같은 추출 규칙입니다. 문서가 바뀔 때 100ms 모아 최근 50개를 JSON으로 보냅니다.
     * Android에는 격리된 실행 공간이 없어 같은 문서에 두 번 넣지 않도록 표시를 남기고, 받는 쪽에서 출처·주소·크기를 다시 확인합니다.
     */
    val SCRIPT = """
        (() => {
            if (window.__innoLiveViewerChat) return;
            window.__innoLiveViewerChat = true;
            let pending = false;
            let previous = "";
            const text = (node) => {
                if (!node) return "";
                if (node.nodeType === Node.TEXT_NODE) return node.textContent || "";
                if (node.nodeType === Node.ELEMENT_NODE && node.tagName === "IMG") {
                    return node.getAttribute("alt") || "";
                }
                return Array.from(node.childNodes).map(text).join("");
            };
            const publish = () => {
                pending = false;
                const renderers = Array.from(document.querySelectorAll(
                    "yt-live-chat-text-message-renderer, yt-live-chat-paid-message-renderer, yt-live-chat-membership-item-renderer, yt-live-chat-paid-sticker-renderer"
                )).slice(-50);
                const messages = renderers.filter(row => !row.hasAttribute("is-deleted"))
                    .map((row, index) => {
                        const author = text(row.querySelector("#author-name")).trim();
                        const body = text(row.querySelector("#message")).trim()
                            || text(row.querySelector("#header-subtext")).trim()
                            || text(row.querySelector("#sticker")).trim();
                        return { id: row.id || `${'$'}{index}:${'$'}{author}:${'$'}{body}`, author, text: body };
                    }).filter(row => row.text.length > 0);
                const value = JSON.stringify({url: location.href, messages,
                    ready: !!document.querySelector("yt-live-chat-renderer")});
                if (value !== previous && window.$NAME) {
                    previous = value;
                    window.$NAME.postMessage(value);
                }
            };
            const schedule = () => {
                if (pending) return;
                pending = true;
                setTimeout(publish, 100);
            };
            new MutationObserver(schedule).observe(document.documentElement, {
                childList: true, subtree: true, characterData: true,
                attributes: true, attributeFilter: ["is-deleted"]
            });
            publish();
        })();
    """.trimIndent()

    /** 형식이 맞지 않거나 크기를 넘는 메시지는 버립니다. */
    fun parse(json: String?): YouTubeChatSnapshot? {
        if (json == null || json.length > MAX_PAYLOAD_BYTES ||
            json.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return null
        return runCatching {
            val root = JSONObject(json)
            val rows = root.getJSONArray("messages")
            YouTubeChatSnapshot(
                url = root.getString("url"),
                messages = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    YouTubeChatMessage(row.getString("id"), row.getString("author"), row.getString("text"))
                },
                ready = root.getBoolean("ready"),
            )
        }.getOrNull()
    }

    /** 지금 읽는 동영상의 채팅 문서에서 온 상태만 받습니다. 이전 동영상 문서의 늦은 메시지는 버립니다. */
    fun accept(json: String?, document: String): YouTubeChatSnapshot? = parse(json)?.takeIf {
        it.url == document || YouTubeViewerChatPolicy.allowsNavigation(it.url, document)
    }
}

private val ChatAvatarColor = Color(0xFF32ADE6).copy(alpha = 0.7f)

/** iOS `YouTubeChatMessageList`와 같은 반투명 메시지 행입니다. 새 메시지가 오면 맨 아래로 따라갑니다. */
@Composable
internal fun YouTubeChatMessageList(messages: List<YouTubeChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("youtube-chat-messages"),
        // 메시지가 적을 때도 iOS처럼 아래쪽부터 쌓습니다.
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        items(messages, key = { it.id }) { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag("youtube-chat-message-${message.id}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(ChatAvatarColor, CircleShape)
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = message.author.trim('@').prefixCharacters(1).uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(message.author) }
                        append("\n")
                        append(message.text)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = message.author + "  " + message.text },
                )
            }
        }
    }
}
