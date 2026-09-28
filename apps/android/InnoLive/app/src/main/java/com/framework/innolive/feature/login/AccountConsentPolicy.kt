package com.framework.innolive.feature.login

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/** Tests replace the document download, while still rendering real HTML in WebView. */
internal val LocalAccountConsentPolicyLoader = compositionLocalOf<suspend (String) -> String> {
    ::downloadAccountConsentPolicy
}

private val policyClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .callTimeout(15, TimeUnit.SECONDS)
    .build()

private suspend fun downloadAccountConsentPolicy(url: String): String = withContext(Dispatchers.IO) {
    require(isAccountConsentPolicyUrl(url))
    policyClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        check(response.isSuccessful)
        check(response.header("Content-Type")?.startsWith("text/html", ignoreCase = true) == true)
        response.body.string().also { check(it.isNotBlank()) }
    }
}

internal fun isAccountConsentPolicyUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == "innolive.studio" &&
        uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
        uri.path.trimEnd('/') in setOf("/ko/privacy", "/en/privacy", "/ja/privacy")
}.getOrDefault(false)

/** Hide fixed navigation and the decorative wordmark, preserving policy and footer details. */
internal fun accountConsentPolicyHtml(html: String): String {
    val style = """
        <style id="innolive-account-consent">
        header[data-fixed-header] { display: none !important; }
        header[data-fixed-header] + div { padding-top: 0 !important; }
        body > footer img[src$="/brand/de-identification-wordmark.svg"],
        body > footer img[alt="De-Identification"] { display: none !important; }
        main[data-page="policy"] > section { padding-top: 16px !important; }
        </style>
    """.trimIndent()
    val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
    return if (head != null) html.replaceRange(head.range.last + 1, head.range.last + 1, style)
    else style + html
}
