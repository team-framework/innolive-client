package com.framework.innolive.feature.live.components

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.ChzzkCategory
import com.framework.innolive.feature.live.ChzzkOAuthConfig
import com.framework.innolive.feature.live.parseChzzkCallback
import com.framework.innolive.feature.live.validationField
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun ChzzkOAuthDialog(
    config: ChzzkOAuthConfig,
    state: String,
    onCode: (String) -> Unit,
    onFailure: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val completed = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface {
            Column(Modifier.padding(12.dp)) {
                Text("치지직 계정 연결")
                TextButton(onClick = onDismiss) { Text("취소") }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp),
                    factory = { context ->
                        WebView(context).also { view ->
                            webView = view
                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.webViewClient = object : WebViewClient() {
                                private fun intercept(url: String): Boolean = try {
                                    val code = parseChzzkCallback(url, config.redirectUri, state) ?: return false
                                    if (completed.compareAndSet(false, true)) onCode(code)
                                    true
                                } catch (exception: Exception) {
                                    if (completed.compareAndSet(false, true)) {
                                        onFailure(exception.message ?: "치지직 인증을 완료하지 못했습니다.")
                                    }
                                    true
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    request.isForMainFrame && intercept(request.url.toString())

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (intercept(url)) view.stopLoading()
                                }
                            }
                            view.loadUrl(config.authorizeUrl)
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun ChzzkSettingsDialog(
    settings: ChzzkBroadcastSettings,
    accountLabel: String,
    canPrepare: Boolean,
    canConnect: Boolean,
    canDisconnect: Boolean,
    isBusy: Boolean,
    onChanged: (ChzzkBroadcastSettings) -> Unit,
    onRefreshAccount: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSearch: suspend (String) -> List<ChzzkCategory>,
    onPrepare: () -> Unit,
    onDismiss: () -> Unit,
    onChangePlatform: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptyList<ChzzkCategory>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.heightIn(max = 700.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("치지직 방송 설정")
                TextButton(onClick = onChangePlatform, enabled = !isBusy) { Text("플랫폼 변경") }
                Text(accountLabel)
                Row {
                    TextButton(onClick = onRefreshAccount, enabled = canConnect && !isBusy) { Text("계정 상태 다시 확인") }
                    Button(onClick = onConnect, enabled = canConnect && !isBusy) { Text("연결 / 재연결") }
                    TextButton(onClick = onDisconnect, enabled = canDisconnect && !isBusy) { Text("연결 해제") }
                }
                OutlinedTextField(settings.title, { onChanged(settings.copy(title = it)) },
                    label = { Text("방송 제목 (최대 100자)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(query, { query = it }, label = { Text("카테고리 검색") },
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    scope.launch {
                        message = null
                        runCatching { onSearch(query) }
                            .onSuccess { categories = it; if (it.isEmpty()) message = "검색 결과가 없습니다." }
                            .onFailure { message = "카테고리를 조회하지 못했습니다. 다시 검색하세요." }
                    }
                }, enabled = query.isNotBlank() && !isBusy) { Text("검색") }
                categories.forEach { category ->
                    TextButton(onClick = {
                        onChanged(settings.copy(categoryType = category.type, categoryId = category.id))
                        categories = emptyList()
                    }) { Text("${category.value} (${category.type})") }
                }
                Text("선택된 카테고리: ${settings.categoryType} ${settings.categoryId}")
                Text("설정을 모두 비우면 준비할 때 서버의 기본값을 조회해 사용합니다.")
                TextButton(onClick = { onChanged(settings.copy(categoryType = "", categoryId = "")) }) {
                    Text("카테고리 해제")
                }
                OutlinedTextField(settings.tags.joinToString(","), { raw ->
                    onChanged(settings.copy(tags = if (raw.isBlank()) emptyList()
                        else raw.split(',').map(String::trim)))
                }, label = { Text("태그 (쉼표로 구분, 최대 5개)") },
                    modifier = Modifier.fillMaxWidth())
                val invalidField = settings.validationField()
                if (invalidField != null) Text("$invalidField 입력을 확인하세요. 태그는 각 15자 이하의 문자·숫자만 가능합니다.",
                    color = Color.Red)
                message?.let { Text(it, color = Color.Red) }
                Row {
                    Button(onClick = onPrepare, enabled = canPrepare && !isBusy && invalidField == null) {
                        Text("방송 준비")
                    }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
            }
        }
    }
}
