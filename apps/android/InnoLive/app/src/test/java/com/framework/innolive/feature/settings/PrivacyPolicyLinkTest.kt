package com.framework.innolive.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyPolicyLinkTest {
    @Test
    fun usesTheReviewedPolicyPathForSupportedLanguagesAndKoreanFallback() {
        assertEquals("https://innolive.studio/ko/privacy", privacyPolicyUrlForLanguage("ko"))
        assertEquals("https://innolive.studio/en/privacy", privacyPolicyUrlForLanguage("en-US"))
        assertEquals("https://innolive.studio/ja/privacy", privacyPolicyUrlForLanguage("ja-JP"))
        assertEquals("https://innolive.studio/ko/privacy", privacyPolicyUrlForLanguage("fr-FR"))
        assertEquals("https://innolive.studio/ko/privacy", privacyPolicyUrlForLanguage(""))
    }
}
