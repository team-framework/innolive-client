package com.framework.innolive.feature.live.components

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.SessionSnapshot
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 시청자 채팅 문서는 라이브 중인 YouTube 동영상 하나에만 묶이고 계정 토큰과는 무관합니다
 * (contracts/api/youtube-viewer-chat-v1.md). iOS `YouTubeViewerChatPolicy`와 같은 규칙입니다.
 */
object YouTubeViewerChatPolicy {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private const val HOST = "www.youtube.com"
    private const val PATH = "/live_chat"

    /** 11자 동영상 ID만 허용합니다. 그 밖의 값은 채팅 주소를 만들지 않습니다. */
    fun url(videoId: String?): String? {
        if (videoId == null || !videoIdPattern.matches(videoId)) return null
        return "https://$HOST$PATH?is_popout=1&v=$videoId"
    }

    /**
     * 라이브가 시작된 YouTube 대상의 채팅 주소입니다. iOS `hasStartedBroadcast`와 같이 `broadcast_phase`가
     * `live`이고 `status`가 `stopped`가 아니면 보여 줍니다. 직접 송출은 라이브 중에도 `status`가 `idle`이므로
     * (direct-publish-v1.md) `broadcastState()` 대신 이 규칙을 씁니다. 종료·제거된 대상과 세션 없음은 null입니다.
     * [settings]는 이 방송을 준비한 YouTube 설정이며, 채팅이 나오지 않는 방송이면 문서를 읽지 않도록 null입니다.
     */
    fun activeUrl(snapshot: SessionSnapshot?, settings: BroadcastSettings? = null): String? {
        val target = snapshot?.targets?.firstOrNull { it.provider == "youtube" } ?: return null
        if (target.broadcastPhase != "live" || target.status == "stopped") return null
        if (settings != null && !showsChat(settings)) return null
        return url(target.videoId)
    }

    /**
     * 로그인하지 않은 채팅 WebView는 비공개 방송의 채팅을 받을 수 없고, YouTube는 아동용 방송의 채팅을 끕니다.
     * 준비 설정을 모르면(앱을 다시 열어 이어 받은 세션 등) 이전처럼 채팅을 읽어 봅니다.
     */
    internal fun showsChat(settings: BroadcastSettings): Boolean =
        settings.privacy != "private" && settings.madeForKids != true

    /** 같은 동영상의 `live_chat` 문서로 가는 HTTPS 메인 프레임 이동만 허용합니다. */
    fun allowsNavigation(candidate: String?, document: String): Boolean {
        val uri = candidate?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        if (uri.scheme != "https" || uri.host != HOST || uri.port != -1 || uri.userInfo != null ||
            uri.rawPath != PATH) return false
        val expected = queryValues(document, "v").singleOrNull() ?: return false
        val ids = queryValues(candidate, "v")
        return ids.size == 1 && ids.single() == expected
    }

    /**
     * `shouldOverrideUrlLoading`의 판단입니다. iOS처럼 링크·폼처럼 사용자 동작으로 시작한 이동과 하위 프레임 이동은
     * 막습니다. 문서를 읽는 중 리디렉션(동의 화면 등)이 막히면 시간 초과를 기다리지 않고 실패 화면을 보여 줍니다.
     */
    internal fun navigationDecision(
        candidate: String?,
        document: String,
        isForMainFrame: Boolean,
        hasGesture: Boolean,
        isRedirect: Boolean,
        documentLoaded: Boolean,
    ): ViewerChatNavigation = when {
        isForMainFrame && !hasGesture && allowsNavigation(candidate, document) -> ViewerChatNavigation.ALLOW
        isForMainFrame && (isRedirect || !documentLoaded) -> ViewerChatNavigation.BLOCK_AND_FAIL
        else -> ViewerChatNavigation.BLOCK
    }

    /** `shouldOverrideUrlLoading`은 POST에서 호출되지 않으므로 메인 프레임의 GET 외 요청(폼 제출)은 요청 단계에서 막습니다. */
    internal fun blocksMainFrameRequest(method: String?, isForMainFrame: Boolean): Boolean =
        isForMainFrame && !method.equals("GET", ignoreCase = true)

    private fun queryValues(url: String, name: String): List<String> {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyList()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key != name) null else URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
    }
}

internal enum class ViewerChatNavigation { ALLOW, BLOCK, BLOCK_AND_FAIL }

/**
 * 채팅 문서 한 번의 읽기 상태와 받은 메시지입니다. iOS `YouTubeViewerChatLoadState`와 같습니다.
 * [attempt]가 다른 이전 WebView의 콜백과 실패 뒤 늦게 온 문서 콜백은 무시합니다.
 */
internal data class ViewerChatLoadState(
    val attempt: Int = 0,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val messages: List<YouTubeChatMessage> = emptyList(),
    val chatReady: Boolean = false,
) {
    /** 문서를 새로 읽기 시작하면 이전 메시지와 준비 상태를 비웁니다. */
    fun pageStarted(from: Int) =
        if (ignores(from)) this else copy(loading = true, messages = emptyList(), chatReady = false)

    fun pageFinished(from: Int) = if (ignores(from)) this else copy(loading = false)

    fun fail(from: Int) = if (from != attempt) this else copy(loading = false, failed = true)

    /** 문서를 다 받은 뒤에도 채팅 목록(`yt-live-chat-renderer`)이 나타나지 않았을 때만 실패로 바꿉니다. */
    fun readinessTimeout(from: Int) = if (ignores(from) || !awaitingReady) this else fail(from)

    /** iOS처럼 지금 문서가 보낸 상태로 메시지를 바꾸고, 채팅 목록이 준비됐으면 읽기 중·실패 표시를 거둡니다. */
    fun received(from: Int, snapshot: YouTubeChatSnapshot): ViewerChatLoadState {
        if (from != attempt) return this
        val next = copy(messages = snapshot.visibleMessages, chatReady = snapshot.ready)
        return if (snapshot.ready) next.copy(loading = false, failed = false) else next
    }

    fun retry() = ViewerChatLoadState(attempt = attempt + 1)

    /** 문서를 다 받았지만 채팅 목록을 아직 찾지 못한 상태입니다. 이때부터 준비 시간 제한을 셉니다. */
    val awaitingReady: Boolean get() = !loading && !failed && !chatReady

    private fun ignores(from: Int) = from != attempt || failed
}

/**
 * 채팅 WebView가 남긴 YouTube 쿠키와 WebStorage를 지웁니다. Android WebView는 iOS의 비영속 저장소와 달리 앱 전체
 * 저장소를 공유하므로 채팅 수신 WebView가 사라질 때(방송 종료)와 로그아웃·계정 삭제 때 정리합니다. 치지직 OAuth 등 다른 WebView의 쿠키는
 * 남기도록 YouTube 도메인만 지웁니다.
 *
 * YouTube는 방문자 쿠키(`YSC`, `VISITOR_INFO1_LIVE` 등)를 `Partitioned`로 내려 WebView가 `youtube.com` 파티션에
 * 저장합니다. 쿠키는 이름·도메인·경로와 파티션이 모두 같아야 덮어써지므로 파티션 쿠키와 일반 쿠키를 모두 만료합니다.
 */
internal object YouTubeViewerChatWebData {
    internal val cookieUrls = listOf(
        "https://www.youtube.com/",
        "https://www.youtube.com/live_chat",
        "https://youtube.com/",
        "https://m.youtube.com/",
        "https://consent.youtube.com/",
    )
    internal val storageOrigins = listOf(
        "https://www.youtube.com",
        "https://youtube.com",
        "https://m.youtube.com",
        "https://consent.youtube.com",
    )

    /** 채팅 문서가 남기는 것으로 기기에서 확인한 이름과 동의 쿠키입니다. 조회에 보이지 않아도 `.youtube.com`에서 만료합니다. */
    internal val knownCookieNames = setOf(
        "YSC", "VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "__Secure-ROLLOUT_TOKEN", "__Secure-YNID",
        "__Secure-BUCKET", "__Secure-YEC", "PREF", "GPS", "SOCS", "CONSENT",
    )
    private const val COOKIE_DOMAIN = ".youtube.com"
    private val cookiePaths = listOf("/", "/live_chat")
    internal const val LOG_TAG = "YouTubeViewerChat"

    /** 수신 WebView를 없앤 뒤 파괴된 WebView의 늦은 응답이 쿠키를 다시 쓰지 않도록 기다리는 시간입니다. */
    internal const val CLOSE_CLEAR_DELAY_MILLIS = 1_000L

    /** 지금 살아 있는 채팅 수신 WebView 수입니다. 새 동영상 문서를 읽는 중에는 이전 문서의 정리를 미룹니다. */
    internal val activeWebViews = AtomicInteger(0)

    interface Store {
        fun cookies(url: String): String?
        /** 결과는 같은 스레드로 돌아와야 합니다(`CookieManager.setCookie`의 `ValueCallback`). */
        fun setCookie(url: String, value: String, onResult: (Boolean) -> Unit)
        fun flushCookies()
        fun deleteStorage(origin: String)
    }

    /** [remaining]은 만료·flush 뒤 YouTube 주소에서 다시 조회한 쿠키 이름입니다. 비어 있어야 정상입니다. */
    data class ClearResult(val requested: Int, val failed: Int, val remaining: Set<String>)

    /** 모든 만료 요청의 결과를 받은 뒤 flush하고 남은 쿠키를 다시 확인해 [onComplete]로 알립니다. */
    fun clear(store: Store, onComplete: (ClearResult) -> Unit = {}) {
        val requests = expiryRequests(store)
        storageOrigins.forEach(store::deleteStorage)
        var pending = requests.size
        var failed = 0
        val finish = {
            store.flushCookies()
            onComplete(ClearResult(requests.size, failed, remainingNames(store)))
        }
        if (requests.isEmpty()) return finish()
        for ((url, value) in requests) {
            store.setCookie(url, value) { ok ->
                if (!ok) failed++
                pending--
                if (pending == 0) finish()
            }
        }
    }

    /**
     * 주소마다 조회한 쿠키는 그 호스트 전용으로, 조회한 이름과 [knownCookieNames]는 `.youtube.com`으로 한 번씩 만료합니다.
     * `.youtube.com` 쿠키는 어느 YouTube 주소로 지워도 같은 쿠키이므로 첫 주소만 씁니다.
     */
    internal fun expiryRequests(store: Store): List<Pair<String, String>> {
        val found = cookieUrls.associateWith { cookieNames(store.cookies(it)) }
        val hostOnly = found.flatMap { (url, names) -> names.flatMap { name -> expiredCookies(name, null).map { url to it } } }
        val domainNames = found.values.flatten().toSet() + knownCookieNames
        val domain = domainNames.flatMap { name -> expiredCookies(name, COOKIE_DOMAIN).map { cookieUrls.first() to it } }
        return hostOnly + domain
    }

    internal fun remainingNames(store: Store): Set<String> =
        cookieUrls.flatMap { cookieNames(store.cookies(it)) }.toSet()

    /** `CookieManager.getCookie`의 `a=1; b=2` 형식에서 이름만 꺼냅니다. */
    internal fun cookieNames(header: String?): Set<String> =
        header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * 이름만으로는 쿠키의 경로와 파티션을 알 수 없으므로 루트와 채팅 경로, 일반과 `Partitioned`를 모두 만료합니다.
     * `Partitioned` 쿠키의 파티션은 WebView가 요청 주소의 사이트(`youtube.com`)로 정합니다.
     */
    internal fun expiredCookies(name: String, domain: String?): List<String> = cookiePaths.flatMap { path ->
        val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path; Secure" +
            (domain?.let { "; Domain=$it" } ?: "")
        listOf(expired, "$expired; SameSite=None; Partitioned")
    }

    /**
     * 앱 WebView 저장소를 지웁니다. WebView API는 UI 스레드에서 부르며, 없앤 WebView가 파괴되고 남은 응답이
     * 끝나도록 [delayMillis] 뒤에 실행합니다. [skipWhileActive]이면 그때 다른 채팅 WebView(바뀐 동영상)가 읽는 중일 때
     * 건너뛰고 그 WebView가 사라질 때 지웁니다. WebView가 없는 기기에서는 건너뜁니다. 쿠키 값은 기록하지 않습니다.
     */
    fun clearAppWebData(reason: String, delayMillis: Long = 0L, skipWhileActive: Boolean = false) {
        Handler(Looper.getMainLooper()).postDelayed({
            val active = activeWebViews.get()
            if (skipWhileActive && active > 0) {
                Log.d(LOG_TAG, "viewer_chat_cookies_clear_skipped reason=$reason active=$active")
                return@postDelayed
            }
            runCatching {
                clear(AndroidStore) { result ->
                    Log.d(LOG_TAG, "viewer_chat_cookies_cleared reason=$reason requested=${result.requested} " +
                        "failed=${result.failed} remaining=${result.remaining.size}" +
                        result.remaining.takeIf { it.isNotEmpty() }?.joinToString(",", " remaining_names=").orEmpty())
                }
            }.onFailure { Log.w(LOG_TAG, "viewer_chat_cookies_clear_failed reason=$reason type=${it.javaClass.simpleName}") }
        }, delayMillis)
    }

    private object AndroidStore : Store {
        override fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)
        override fun setCookie(url: String, value: String, onResult: (Boolean) -> Unit) =
            CookieManager.getInstance().setCookie(url, value) { ok -> onResult(ok == true) }
        override fun flushCookies() = CookieManager.getInstance().flush()
        override fun deleteStorage(origin: String) = WebStorage.getInstance().deleteOrigin(origin)
    }
}

/** 없앤 채팅 WebView 정리 순서입니다. 콜백을 떼고 읽기를 멈춘 뒤 빈 문서로 비우고 파괴합니다. */
internal interface ViewerChatWebSurface {
    fun detachClient()
    fun stopLoading()
    fun loadBlank()
    fun destroy()
}

internal fun releaseViewerChat(surface: ViewerChatWebSurface) {
    surface.detachClient()
    surface.stopLoading()
    surface.loadBlank()
    surface.destroy()
}

/** 한 동영상 채팅 문서의 수신 상태입니다. 방송 화면에 하나만 두고 기본 페이지의 오버레이가 이 상태를 그립니다. */
@Stable
internal class YouTubeViewerChatState(val url: String) {
    var load by mutableStateOf(ViewerChatLoadState())

    fun retry() {
        load = load.retry()
    }

    /**
     * 수신 WebView의 메시지입니다. 지난 시도나 다른 동영상 문서에서 온 메시지는 버립니다.
     * 메시지 수나 준비 상태가 바뀌었으면 true를 돌려줍니다.
     */
    internal fun receive(from: Int, json: String?): Boolean {
        val snapshot = YouTubeChatBridge.accept(json, url) ?: return false
        val before = load
        load = before.received(from, snapshot)
        return load.messages.size != before.messages.size || load.chatReady != before.chatReady
    }
}

/** 주소가 사라지면(방송 종료·대상 제거·세션 종료) null이 되고, 동영상이 바뀌면 새 상태를 만듭니다. */
@Composable
internal fun rememberYouTubeViewerChatState(url: String?): YouTubeViewerChatState? =
    remember(url) { url?.let(::YouTubeViewerChatState) }

/**
 * 메시지를 받는 보이지 않는 WebView입니다. iOS처럼 보통 크기로 배치해 YouTube가 채팅을 그리게 하고, 채팅을 숨겨도
 * 계속 받습니다. 넘기는 페이지 밖(방송 화면 맨 아래 층)에 두어 페이지 이동·얼굴 선택으로 다시 읽지 않습니다.
 * 같은 크기의 검은 판으로 덮어 보이지 않게 합니다. 투명도 0인 뷰는 그리기를 건너뛰어 WebView가 화면을 갱신하지 않을 수
 * 있기 때문입니다. 수신기가 사라지면 YouTube 쿠키와 WebStorage를 지웁니다.
 */
@Composable
internal fun YouTubeViewerChatReceiver(state: YouTubeViewerChatState, modifier: Modifier = Modifier) {
    val attempt = state.load.attempt
    val awaitingReady = state.load.awaitingReady
    LaunchedEffect(state, attempt, awaitingReady) {
        if (awaitingReady) {
            delay(READINESS_TIMEOUT_MILLIS)
            state.load = state.load.readinessTimeout(attempt)
            if (state.load.failed) Log.d(YouTubeViewerChatWebData.LOG_TAG, "viewer_chat_readiness_timeout")
        }
    }
    // WebView 파괴(AndroidView onRelease) 뒤에 실행되도록 지연해 정리합니다.
    DisposableEffect(state) {
        onDispose {
            YouTubeViewerChatWebData.clearAppWebData(
                "close", YouTubeViewerChatWebData.CLOSE_CLEAR_DELAY_MILLIS, skipWhileActive = true,
            )
        }
    }
    Box(modifier.size(ReceiverWidth, ReceiverHeight).clearAndSetSemantics {}) {
        key(state.url, attempt) {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("viewer-chat-receiver"),
                factory = { context ->
                    createViewerChatWebView(
                        context,
                        state.url,
                        onStarted = { state.load = state.load.pageStarted(attempt) },
                        onFinished = { state.load = state.load.pageFinished(attempt) },
                        onFailed = {
                            state.load = state.load.fail(attempt)
                            Log.d(YouTubeViewerChatWebData.LOG_TAG, "viewer_chat_load_failed")
                        },
                        onMessage = { json ->
                            // 기기 확인용으로 수와 준비 상태만 남기고 작성자·내용은 기록하지 않습니다.
                            if (state.receive(attempt, json)) {
                                Log.d(YouTubeViewerChatWebData.LOG_TAG,
                                    "viewer_chat_messages count=${state.load.messages.size} ready=${state.load.chatReady}")
                            }
                        },
                    )
                },
                onRelease = { view -> releaseViewerChat(AndroidWebSurface(view)) },
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

/**
 * iOS `YouTubeViewerChatOverlay`와 같은 방송 화면 채팅입니다. 최대 폭 280dp, 메시지 영역은 세로 136dp·가로 106dp이고
 * 그 아래 44dp 원형 채팅 메뉴를 둡니다. 숨기면 메시지 영역만 접고 수신은 [YouTubeViewerChatReceiver]가 계속합니다.
 */
@Composable
internal fun YouTubeViewerChatOverlay(
    state: YouTubeViewerChatState,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = OverlayMaxWidth).testTag("youtube-viewer-chat-overlay"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!hidden) {
            YouTubeViewerChatContent(
                load = state.load,
                onRetry = state::retry,
                modifier = Modifier.width(OverlayMaxWidth).height(if (compact) 106.dp else 136.dp),
            )
        }
        YouTubeViewerChatMenu(hidden = hidden, onToggleHidden = onToggleHidden)
    }
}

/** 메시지 행과 연결 중·대기·실패 안내입니다. iOS `YouTubeViewerChatContent`의 화면 부분과 같습니다. */
@Composable
private fun YouTubeViewerChatContent(load: ViewerChatLoadState, onRetry: () -> Unit, modifier: Modifier) {
    Box(modifier) {
        YouTubeChatMessageList(messages = load.messages, modifier = Modifier.fillMaxSize())
        if (load.messages.isEmpty() && !load.failed) {
            Column(
                modifier = Modifier.align(Alignment.Center).chatNoticeSurface().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!load.chatReady) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                }
                Text(
                    text = stringResource(
                        if (load.chatReady) R.string.viewer_chat_waiting else R.string.viewer_chat_connecting,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (load.failed) {
            Column(
                modifier = Modifier.align(Alignment.Center).chatNoticeSurface().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.viewer_chat_load_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/** 방송 화면의 반투명 카메라 도구 버튼과 같은 어두운 유리 바탕입니다. */
private fun Modifier.chatNoticeSurface(): Modifier = this
    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))

/**
 * iOS 채팅 메뉴처럼 원형 버튼을 누르면 "채팅 숨기기"·"채팅 표시" 한 항목을 띄웁니다. 보일 때는 채운 말풍선,
 * 숨겼을 때는 테두리 말풍선을 씁니다.
 */
@Composable
private fun YouTubeViewerChatMenu(hidden: Boolean, onToggleHidden: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.viewer_chat_menu)
    val icon: ImageVector = if (hidden) Icons.Outlined.ChatBubbleOutline else Icons.Filled.ChatBubble
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier
                .size(44.dp)
                .semantics { contentDescription = menuLabel }
                .testTag("youtube-chat-menu"),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (hidden) R.string.viewer_chat_show else R.string.viewer_chat_hide)) },
                leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
                onClick = {
                    expanded = false
                    onToggleHidden()
                },
            )
        }
    }
}

private class AndroidWebSurface(private val view: WebView) : ViewerChatWebSurface {
    override fun detachClient() {
        view.webViewClient = WebViewClient()
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.removeWebMessageListener(view, YouTubeChatBridge.NAME)
            }
        }
        view.removeJavascriptInterface(YouTubeChatBridge.NAME)
    }
    override fun stopLoading() = view.stopLoading()
    override fun loadBlank() = view.loadUrl("about:blank")
    override fun destroy() {
        view.destroy()
        YouTubeViewerChatWebData.activeWebViews.decrementAndGet()
    }
}

/** `WebMessageListener`를 지원하지 않는 오래된 WebView에서만 쓰는 대체 통로입니다. 백그라운드 스레드에서 불립니다. */
private class ViewerChatJavascriptBridge(private val onMessage: (String?) -> Unit) {
    @JavascriptInterface
    fun postMessage(json: String?) = onMessage(json)
}

/**
 * iOS가 데스크톱 Safari 식별자를 쓰는 것처럼 데스크톱 Chrome 식별자로 채팅 문서를 받습니다. 추출 스크립트가 기대하는
 * 데스크톱 채팅 화면을 받기 위해서이며, Chrome 버전은 기기 WebView 값을 그대로 씁니다.
 */
internal fun viewerChatUserAgent(defaultAgent: String?): String {
    val chrome = defaultAgent?.let { Regex("Chrome/([0-9.]+)").find(it)?.groupValues?.get(1) } ?: FALLBACK_CHROME_VERSION
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
}

internal fun createViewerChatWebView(
    context: Context,
    document: String,
    onStarted: () -> Unit,
    onFinished: () -> Unit,
    onFailed: () -> Unit,
    onMessage: (String?) -> Unit,
    /** 테스트는 네트워크 없이 같은 주소의 고정 문서를 읽도록 바꿉니다. */
    load: WebView.() -> Unit = { loadUrl(document) },
): WebView = WebView(context).apply {
    YouTubeViewerChatWebData.activeWebViews.incrementAndGet()
    // 화면에 보이지 않는 수신기이므로 초점·화면 낭독기 대상에서 뺍니다.
    isFocusable = false
    isFocusableInTouchMode = false
    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.mediaPlaybackRequiresUserGesture = true
    settings.userAgentString = viewerChatUserAgent(runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull())
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    // 메시지는 YouTube 메인 프레임에서 온 것만 받습니다.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(this, YouTubeChatBridge.NAME, setOf(YouTubeChatBridge.ORIGIN)) {
                _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && sourceOrigin.toString() == YouTubeChatBridge.ORIGIN) {
                onMessage(runCatching { message.data }.getOrNull())
            }
        }
        Log.d(YouTubeViewerChatWebData.LOG_TAG, "viewer_chat_bridge=web_message_listener")
    } else {
        val main = Handler(Looper.getMainLooper())
        addJavascriptInterface(ViewerChatJavascriptBridge { json -> main.post { onMessage(json) } }, YouTubeChatBridge.NAME)
        Log.d(YouTubeViewerChatWebData.LOG_TAG, "viewer_chat_bridge=javascript_interface")
    }
    webViewClient = object : WebViewClient() {
        @Volatile private var documentLoaded = false
        // shouldInterceptRequest는 UI 스레드 밖에서 불립니다. 막은 폼 제출의 중단 오류는 실패로 보지 않습니다.
        private val blockedSubmission = AtomicReference<String?>(null)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            when (YouTubeViewerChatPolicy.navigationDecision(
                candidate = request.url.toString(),
                document = document,
                isForMainFrame = request.isForMainFrame,
                hasGesture = request.hasGesture(),
                isRedirect = request.isRedirect,
                documentLoaded = documentLoaded,
            )) {
                ViewerChatNavigation.ALLOW -> false
                ViewerChatNavigation.BLOCK -> true
                ViewerChatNavigation.BLOCK_AND_FAIL -> {
                    onFailed()
                    true
                }
            }

        // 내용 없는 204 응답은 메인 프레임 이동을 취소하고 현재 채팅 문서를 그대로 둡니다.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!YouTubeViewerChatPolicy.blocksMainFrameRequest(request.method, request.isForMainFrame)) return null
            blockedSubmission.set(request.url.toString())
            return WebResourceResponse(
                "text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)),
            )
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url == blockedSubmission.get()) {
                // 이 WebView 구현이 204를 빈 문서로 표시하면 채팅 대신 실패 안내와 다시 시도를 보여 줍니다.
                view.stopLoading()
                onFailed()
            } else {
                onStarted()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            // iOS의 문서 끝 주입처럼 채팅 문서를 다 읽은 뒤 추출 스크립트를 넣습니다.
            if (YouTubeViewerChatPolicy.allowsNavigation(url, document)) {
                documentLoaded = true
                view.evaluateJavascript(YouTubeChatBridge.SCRIPT, null)
            }
            onFinished()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && request.url.toString() != blockedSubmission.get()) onFailed()
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) onFailed()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            onFailed()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onFailed()
            return true
        }
    }
    load()
}

/** iOS와 같이 문서를 다 받은 뒤 채팅 목록을 기다리는 시간입니다. */
internal const val READINESS_TIMEOUT_MILLIS = 12_000L
private const val FALLBACK_CHROME_VERSION = "141.0.0.0"
private val OverlayMaxWidth = 280.dp
private val ReceiverWidth = 360.dp
private val ReceiverHeight = 480.dp
