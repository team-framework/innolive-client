package com.framework.innolive.feature.live.components

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.framework.innolive.feature.live.ChzzkOAuthConfig
import com.framework.innolive.feature.live.parseChzzkCallback
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun ChzzkOAuthDialog(
    config: ChzzkOAuthConfig,
    state: String,
    onCode: (String) -> Unit,
    onFailure: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val completed = remember(config, state) { AtomicBoolean(false) }
    var loading by remember(config, state) { mutableStateOf(true) }
    var loadError by remember(config, state) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(config, state) { mutableIntStateOf(0) }
    val loadFailure = "치지직 로그인 페이지를 불러오지 못했습니다. 다시 시도하세요."
    val dismiss = { if (completed.compareAndSet(false, true)) onDismiss() }

    LaunchedEffect(loading, loadAttempt) {
        if (loading) {
            delay(30_000)
            if (!completed.get()) {
                loadError = loadFailure
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("치지직 계정 연결")
                TextButton(onClick = dismiss) { Text("취소") }
                // A bounded, weighted area gives the native WebView an exact height.
                // heightIn alone lets a page without intrinsic height measure to zero.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (loadError != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(loadError.orEmpty())
                            TextButton(onClick = {
                                loadError = null
                                loading = true
                                loadAttempt++
                            }) { Text("다시 시도") }
                        }
                    } else {
                        key(config, state, loadAttempt) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        webViewClient = object : WebViewClient() {
                                            private fun intercept(url: String): Boolean = try {
                                                val code = parseChzzkCallback(url, config.redirectUri, state) ?: return false
                                                if (completed.compareAndSet(false, true)) onCode(code)
                                                true
                                            } catch (_: SecurityException) {
                                                if (completed.compareAndSet(false, true)) {
                                                    onFailure("OAuth state 또는 콜백 형식이 올바르지 않습니다. 다시 연결하세요.")
                                                }
                                                true
                                            } catch (_: IOException) {
                                                if (completed.compareAndSet(false, true)) {
                                                    onFailure("치지직 계정 연결이 취소되거나 거절됐습니다. 다시 연결하세요.")
                                                }
                                                true
                                            } catch (_: Exception) {
                                                if (completed.compareAndSet(false, true)) {
                                                    onFailure("치지직 인증을 완료하지 못했습니다. 다시 연결하세요.")
                                                }
                                                true
                                            }

                                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                                request.isForMainFrame && intercept(request.url.toString())

                                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                                if (intercept(url)) view.stopLoading()
                                                else if (!completed.get()) loading = true
                                            }

                                            override fun onPageFinished(view: WebView, url: String) {
                                                if (!completed.get()) loading = false
                                            }

                                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                                if (request.isForMainFrame && !completed.get()) {
                                                    loadError = loadFailure
                                                    loading = false
                                                }
                                            }

                                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                                if (request.isForMainFrame && !completed.get()) {
                                                    loadError = loadFailure
                                                    loading = false
                                                }
                                            }

                                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                                handler.cancel()
                                                if (!completed.get()) {
                                                    loadError = "치지직 로그인 페이지의 보안 연결을 확인하지 못했습니다. 네트워크를 확인하고 다시 시도하세요."
                                                    loading = false
                                                }
                                            }
                                        }
                                        loadUrl(config.authorizeUrl)
                                    }
                                },
                                onRelease = {
                                    it.webViewClient = WebViewClient()
                                    it.stopLoading()
                                    it.destroy()
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
