package com.framework.innolive.feature.settings

import java.util.Locale

private const val PRIVACY_POLICY_BASE_URL = "https://innolive.studio"

/** Returns the reviewed policy URL for an app language, falling back to Korean. */
internal fun privacyPolicyUrlForLanguage(languageTag: String): String {
    val language = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    val supportedLanguage = language.takeIf { it in setOf("ko", "en", "ja") } ?: "ko"
    return "$PRIVACY_POLICY_BASE_URL/$supportedLanguage/privacy"
}
