package com.framework.innolive.feature.settings

import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun linkWithoutABrowserReportsFailureInsteadOfCrashing() {
        // Compose의 AndroidUriHandler는 열 앱이 없으면 IllegalArgumentException을 던집니다.
        val noBrowser = uriHandler { throw IllegalArgumentException("Can't open $it.") }
        val blockedByPolicy = uriHandler { throw SecurityException("blocked") }

        assertFalse(noBrowser.tryOpenUri(privacyPolicyUrlForLanguage("ko")))
        assertFalse(blockedByPolicy.tryOpenUri(privacyPolicyUrlForLanguage("ko")))
    }

    @Test
    fun openedLinkReportsSuccessWithTheRequestedUrl() {
        val opened = mutableListOf<String>()
        val browser = uriHandler { opened += it }

        assertTrue(browser.tryOpenUri(privacyPolicyUrlForLanguage("ja-JP")))
        assertEquals(listOf("https://innolive.studio/ja/privacy"), opened)
    }

    private fun uriHandler(open: (String) -> Unit) = object : UriHandler {
        override fun openUri(uri: String) = open(uri)
    }
}
