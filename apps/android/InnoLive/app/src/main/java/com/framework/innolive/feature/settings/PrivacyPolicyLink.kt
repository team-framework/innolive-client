package com.framework.innolive.feature.settings

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler
import java.util.Locale

private const val PRIVACY_POLICY_BASE_URL = "https://innolive.studio"

/** 앱 언어에 맞는 검토된 개인정보 처리방침 URL을 반환하며, 지원하지 않는 언어는 한국어로 대체합니다. */
internal fun privacyPolicyUrlForLanguage(languageTag: String): String {
    val language = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    val supportedLanguage = language.takeIf { it in setOf("ko", "en", "ja") } ?: "ko"
    return "$PRIVACY_POLICY_BASE_URL/$supportedLanguage/privacy"
}

/**
 * 브라우저가 없거나 업무 프로필 정책으로 막혀 링크를 열 수 없어도 앱이 끝나지 않게 실패를 돌려줍니다.
 * Compose의 [UriHandler]는 열 앱이 없으면 [IllegalArgumentException]으로 감싸 던집니다.
 */
internal fun UriHandler.tryOpenUri(uri: String): Boolean = try {
    openUri(uri)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: IllegalArgumentException) {
    false
} catch (_: SecurityException) {
    false
}
