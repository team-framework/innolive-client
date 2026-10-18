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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.net.URI
import java.net.URLDecoder

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

    private fun queryValues(url: String, name: String): List<String> {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyList()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key != name) null else URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
    }
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

/**
 * 보기 전용 시청자 채팅 창입니다. 앱의 액세스 토큰이나 세션 토큰을 WebView에 넣지 않으며 로그인·링크·폼·
 * 새 창 이동을 막습니다. [url]이 바뀌면 새 문서를 읽고, 창이 닫히면 YouTube 쿠키와 WebStorage를 지웁니다.
 */
@Composable
internal fun YouTubeViewerChatDialog(url: String, onDismiss: () -> Unit) {
    var loading by remember(url) { mutableStateOf(true) }
    var loadFailed by remember(url) { mutableStateOf(false) }
    var loadAttempt by remember(url) { mutableIntStateOf(0) }

    LaunchedEffect(url, loading, loadAttempt) {
        if (loading) {
            delay(LOAD_TIMEOUT_MILLIS)
            loadFailed = true
            loading = false
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
                    if (loadFailed) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.viewer_chat_load_failed))
                            TextButton(onClick = {
                                loadFailed = false
                                loading = true
                                loadAttempt++
                            }) { Text(stringResource(R.string.action_retry)) }
                        }
                    } else {
                        key(url, loadAttempt) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize().semantics { testTag = "viewer-chat-web" },
                                factory = { context -> createViewerChatWebView(context, url,
                                    onLoading = { loading = it },
                                    onFailed = { loadFailed = true; loading = false }) },
                                onRelease = { view ->
                                    view.webViewClient = WebViewClient()
                                    view.stopLoading()
                                    view.loadUrl("about:blank")
                                    view.destroy()
                                },
                            )
                        }
                        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

private fun createViewerChatWebView(
    context: android.content.Context,
    document: String,
    onLoading: (Boolean) -> Unit,
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
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !(request.isForMainFrame && YouTubeViewerChatPolicy.allowsNavigation(request.url.toString(), document))

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onLoading(true)

        override fun onPageFinished(view: WebView, url: String) = onLoading(false)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onFailed()
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
