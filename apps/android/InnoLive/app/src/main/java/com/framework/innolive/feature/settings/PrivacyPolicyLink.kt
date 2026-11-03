package com.framework.innolive.feature.settings

import java.util.Locale

private const val PRIVACY_POLICY_BASE_URL = "https://innolive.studio"

/** 앱 언어에 맞는 검토된 개인정보 처리방침 URL을 반환하며, 지원하지 않는 언어는 한국어로 대체합니다. */
internal fun privacyPolicyUrlForLanguage(languageTag: String): String {
    val language = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    val supportedLanguage = language.takeIf { it in setOf("ko", "en", "ja") } ?: "ko"
    return "$PRIVACY_POLICY_BASE_URL/$supportedLanguage/privacy"
}
