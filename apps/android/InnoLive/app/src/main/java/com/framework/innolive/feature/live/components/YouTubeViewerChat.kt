package com.framework.innolive.feature.live.components

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framework.innolive.R
import com.framework.innolive.feature.live.SessionSnapshot
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
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
     */
    fun activeUrl(snapshot: SessionSnapshot?): String? {
        val target = snapshot?.targets?.firstOrNull { it.provider == "youtube" } ?: return null
        if (target.broadcastPhase != "live" || target.status == "stopped") return null
        return url(target.videoId)
    }

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
 * 채팅 문서 한 번의 읽기 상태입니다. [attempt]가 다른 이전 WebView의 콜백과 실패 뒤 늦게 온 콜백은 무시합니다.
 */
internal data class ViewerChatLoadState(
    val attempt: Int = 0,
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    fun pageStarted(from: Int) = if (ignores(from)) this else copy(loading = true)

    fun pageFinished(from: Int) = if (ignores(from)) this else copy(loading = false)

    fun fail(from: Int) = if (from != attempt) this else copy(loading = false, failed = true)

    /** 시간 초과 시점까지 문서를 다 받지 못했을 때만 실패로 바꿉니다. */
    fun timeout(from: Int) = if (ignores(from) || !loading) this else fail(from)

    fun retry() = ViewerChatLoadState(attempt = attempt + 1)

    private fun ignores(from: Int) = from != attempt || failed
}

/**
 * 채팅 WebView가 남긴 YouTube 쿠키와 WebStorage를 지웁니다. Android WebView는 iOS의 비영속 저장소와 달리 앱 전체
 * 저장소를 공유하므로 창을 닫을 때와 로그아웃·계정 삭제 때 정리합니다. 치지직 OAuth 등 다른 WebView의 쿠키는
 * 남기도록 YouTube 도메인만 지웁니다.
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
    private const val COOKIE_DOMAIN = ".youtube.com"
    private val cookiePaths = listOf("/", "/live_chat")

    interface Store {
        fun cookies(url: String): String?
        fun setCookie(url: String, value: String)
        fun flushCookies()
        fun deleteStorage(origin: String)
    }

    fun clear(store: Store) {
        for (url in cookieUrls) {
            for (name in cookieNames(store.cookies(url))) {
                expiredCookies(name).forEach { store.setCookie(url, it) }
            }
        }
        store.flushCookies()
        storageOrigins.forEach(store::deleteStorage)
    }

    /** `CookieManager.getCookie`의 `a=1; b=2` 형식에서 이름만 꺼냅니다. */
    internal fun cookieNames(header: String?): Set<String> =
        header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.toSet()

    /** 이름만으로는 쿠키의 도메인·경로를 알 수 없으므로 호스트 전용과 `.youtube.com`, 루트와 채팅 경로를 모두 만료합니다. */
    internal fun expiredCookies(name: String): List<String> = cookiePaths.flatMap { path ->
        val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path; Secure"
        listOf(expired, "$expired; Domain=$COOKIE_DOMAIN")
    }

    /**
     * 앱 WebView 저장소를 지웁니다. WebView API는 UI 스레드에서 부르며, 닫힌 창의 WebView 정리가 끝난 뒤 실행되도록
     * 메시지로 미룹니다. WebView가 없는 기기에서는 건너뜁니다.
     */
    fun clearAppWebData() {
        Handler(Looper.getMainLooper()).post { runCatching { clear(AndroidStore) } }
    }

    private object AndroidStore : Store {
        override fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)
        override fun setCookie(url: String, value: String) = CookieManager.getInstance().setCookie(url, value)
        override fun flushCookies() = CookieManager.getInstance().flush()
        override fun deleteStorage(origin: String) = WebStorage.getInstance().deleteOrigin(origin)
    }
}

/** 닫힌 채팅 WebView 정리 순서입니다. 콜백을 떼고 읽기를 멈춘 뒤 빈 문서로 비우고 파괴합니다. */
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

/**
 * 채팅 버튼으로 연 창을 표시합니다. 주소가 사라지면(방송 종료·대상 제거·세션 종료) 열려 있던 창을 닫고, 주소가
 * 바뀌면 [content]가 새 문서를 받습니다.
 */
@Composable
internal fun YouTubeViewerChatHost(
    url: String?,
    isOpen: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (url: String) -> Unit = { YouTubeViewerChatDialog(url = it, onDismiss = onDismiss) },
) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(url) { if (url == null) dismiss() }
    if (isOpen && url != null) content(url)
}

/**
 * 보기 전용 시청자 채팅 창입니다. 앱의 액세스 토큰이나 세션 토큰을 WebView에 넣지 않으며 로그인·링크·폼·
 * 새 창 이동을 막습니다. [url]이 바뀌면 새 문서를 읽고, 창이 닫히면 YouTube 쿠키와 WebStorage를 지웁니다.
 */
@Composable
internal fun YouTubeViewerChatDialog(url: String, onDismiss: () -> Unit) {
    var load by remember(url) { mutableStateOf(ViewerChatLoadState()) }
    val attempt = load.attempt

    LaunchedEffect(url, attempt, load.loading) {
        if (load.loading) {
            delay(LOAD_TIMEOUT_MILLIS)
            load = load.timeout(attempt)
        }
    }
    DisposableEffect(Unit) { onDispose { YouTubeViewerChatWebData.clearAppWebData() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.viewer_chat_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
                Text(stringResource(R.string.viewer_chat_notice), style = MaterialTheme.typography.bodySmall)
                // 높이를 정한 영역이어야 WebView가 0으로 측정되지 않습니다(ChzzkOAuthDialog와 같음).
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (load.failed) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.viewer_chat_load_failed))
                            TextButton(onClick = { load = load.retry() }) { Text(stringResource(R.string.action_retry)) }
                        }
                    } else {
                        key(url, attempt) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize().semantics { testTag = "viewer-chat-web" },
                                factory = { context ->
                                    createViewerChatWebView(
                                        context,
                                        url,
                                        onStarted = { load = load.pageStarted(attempt) },
                                        onFinished = { load = load.pageFinished(attempt) },
                                        onFailed = { load = load.fail(attempt) },
                                    )
                                },
                                onRelease = { view -> releaseViewerChat(AndroidWebSurface(view)) },
                            )
                        }
                        if (load.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

private class AndroidWebSurface(private val view: WebView) : ViewerChatWebSurface {
    override fun detachClient() {
        view.webViewClient = WebViewClient()
    }
    override fun stopLoading() = view.stopLoading()
    override fun loadBlank() = view.loadUrl("about:blank")
    override fun destroy() = view.destroy()
}

private fun createViewerChatWebView(
    context: android.content.Context,
    document: String,
    onStarted: () -> Unit,
    onFinished: () -> Unit,
    onFailed: () -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.mediaPlaybackRequiresUserGesture = true
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
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
                // 이 WebView 구현이 204를 빈 문서로 표시하면 채팅 대신 실패 화면과 다시 시도를 보여 줍니다.
                view.stopLoading()
                onFailed()
            } else {
                onStarted()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (YouTubeViewerChatPolicy.allowsNavigation(url, document)) documentLoaded = true
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
    loadUrl(document)
}

private const val LOAD_TIMEOUT_MILLIS = 30_000L
