package com.framework.innolive.feature.login

import android.content.ActivityNotFoundException
import android.net.Uri
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import com.framework.innolive.R
import com.framework.innolive.feature.settings.privacyPolicyUrlForLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Lets UI tests supply a local document without depending on the public website. */
internal val LocalAccountConsentPolicyUrl = compositionLocalOf<String?> { null }

@Composable
internal fun AccountConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val initialPolicyUrl = LocalAccountConsentPolicyUrl.current
        ?: privacyPolicyUrlForLanguage(LocalConfiguration.current.locales[0].language)
    var policyUrl by remember(initialPolicyUrl) { mutableStateOf(initialPolicyUrl) }
    val loadPolicy = LocalAccountConsentPolicyLoader.current
    val uriHandler = LocalUriHandler.current
    var loadAttempt by remember { mutableIntStateOf(0) }
    var isLoaded by remember(policyUrl, loadAttempt) { mutableStateOf(false) }
    var hasReadToEnd by remember(policyUrl, loadAttempt) { mutableStateOf(false) }
    var hasLoadFailed by remember(policyUrl, loadAttempt) { mutableStateOf(false) }
    var externalLinkFailed by remember(policyUrl) { mutableStateOf(false) }
    var policyHtml by remember(policyUrl, loadAttempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(policyUrl, loadAttempt) {
        try {
            policyHtml = accountConsentPolicyHtml(withTimeout(20_000) { loadPolicy(policyUrl) })
        } catch (_: TimeoutCancellationException) {
            hasLoadFailed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            hasLoadFailed = true
        }
    }
    LaunchedEffect(policyUrl, loadAttempt) {
        delay(20_000)
        if (!isLoaded) hasLoadFailed = true
    }

    // 가로 화면은 높이가 낮아 위아래로 쌓으면 약관 본문이 접혀 보이지 않으므로, 본문과 안내·버튼을 좌우로 나눕니다.
    val header: @Composable () -> Unit = {
            Text(
                text = stringResource(R.string.account_consent_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (externalLinkFailed) {
                Text(
                    text = stringResource(R.string.account_consent_external_link_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
    }
    val policy: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier = modifier) {
                if (hasLoadFailed) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.account_consent_load_failed))
                        TextButton(onClick = {
                            isLoaded = false
                            hasReadToEnd = false
                            hasLoadFailed = false
                            loadAttempt++
                        }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                } else {
                    policyHtml?.let { html ->
                        key(policyUrl, loadAttempt) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize().testTag("accountConsent.policy"),
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = false
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        isVerticalScrollBarEnabled = true
                                        setOnScrollChangeListener { view, _, _, _, _ ->
                                            if (isLoaded && !view.canScrollVertically(1)) {
                                                hasReadToEnd = true
                                            }
                                        }
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                view: WebView,
                                                request: WebResourceRequest,
                                            ): Boolean {
                                                val target = request.url.toString()
                                                if (!request.isForMainFrame) return false
                                                if (sameDocument(policyUrl, target)) return false
                                                if (isAccountConsentPolicyUrl(target)) {
                                                    policyUrl = target
                                                    return true
                                                }
                                                if (request.url.scheme in setOf("https", "mailto")) {
                                                    try {
                                                        uriHandler.openUri(target)
                                                        externalLinkFailed = false
                                                    } catch (_: ActivityNotFoundException) {
                                                        externalLinkFailed = true
                                                    } catch (_: SecurityException) {
                                                        externalLinkFailed = true
                                                    }
                                                }
                                                return true
                                            }

                                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                                isLoaded = false
                                                hasReadToEnd = false
                                            }

                                            override fun onReceivedError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                error: WebResourceError,
                                            ) {
                                                if (request.isForMainFrame) hasLoadFailed = true
                                            }

                                            override fun onReceivedHttpError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                errorResponse: WebResourceResponse,
                                            ) {
                                                if (request.isForMainFrame) hasLoadFailed = true
                                            }

                                            override fun onReceivedSslError(
                                                view: WebView,
                                                handler: SslErrorHandler,
                                                error: SslError,
                                            ) {
                                                handler.cancel()
                                                hasLoadFailed = true
                                            }

                                            override fun onPageFinished(view: WebView, url: String) {
                                                if (hasLoadFailed || !sameDocument(policyUrl, url)) {
                                                    hasLoadFailed = true
                                                    return
                                                }
                                                view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                                                    override fun onComplete(requestId: Long) {
                                                        if (!hasLoadFailed && view.contentHeight > 0) {
                                                            isLoaded = true
                                                            if (!view.canScrollVertically(1)) hasReadToEnd = true
                                                        }
                                                    }
                                                })
                                            }
                                        }
                                        loadDataWithBaseURL(policyUrl, html, "text/html", "UTF-8", null)
                                    }
                                },
                                onRelease = {
                                    it.setOnScrollChangeListener(null)
                                    it.webViewClient = WebViewClient()
                                    it.stopLoading()
                                    it.destroy()
                                },
                            )
                        }
                    }
                    if (!isLoaded) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).testTag("accountConsent.loading"),
                        )
                    }
                }
            }
    }
    val footer: @Composable () -> Unit = {
            Text(
                text = stringResource(R.string.account_consent_refusal),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.account_consent_scroll_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.account_consent_cancel))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = isLoaded && hasReadToEnd && !hasLoadFailed,
                    onClick = onAccept,
                ) {
                    Text(stringResource(R.string.account_consent_accept))
                }
            }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val landscape = maxWidth > maxHeight
            // 창을 화면 크기로 넓혔으므로 바깥을 누르면 닫히던 동작을 직접 이어 줍니다.
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss))
            Surface(
                shape = MaterialTheme.shapes.large,
                modifier = if (landscape) {
                    Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.9f)
                } else {
                    Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.85f)
                },
            ) {
                if (landscape) {
                    Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        policy(Modifier.weight(1f).fillMaxHeight())
                        Column(
                            modifier = Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            header()
                            footer()
                        }
                    }
                } else {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        header()
                        policy(Modifier.weight(1f).fillMaxWidth())
                        footer()
                    }
                }
            }
        }
    }
}

private fun sameDocument(expected: String, actual: String): Boolean {
    if (expected.startsWith("data:")) return actual.startsWith("data:")
    val expectedUri = Uri.parse(expected)
    val actualUri = Uri.parse(actual)
    return isAccountConsentPolicyUrl(actual) && actualUri.host == expectedUri.host &&
        actualUri.path?.trimEnd('/') == expectedUri.path?.trimEnd('/')
}
