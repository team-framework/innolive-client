package com.framework.innolive.feature.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountConsentPolicyTest {
    @Test
    fun onlyPublishedPolicyDocumentsAreHandledInsideTheDialog() {
        listOf("ko", "en", "ja").forEach {
            assertTrue(isAccountConsentPolicyUrl("https://innolive.studio/$it/privacy"))
            assertTrue(isAccountConsentPolicyUrl("https://innolive.studio/$it/privacy/#rights"))
        }
        listOf(
            "http://innolive.studio/en/privacy",
            "https://innolive.studio:8443/en/privacy",
            "https://innolive.studio.evil.example/en/privacy",
            "https://evil.example/en/privacy",
            "https://innolive.studio/en/terms",
            "https://innolive.studio/fr/privacy",
            "https://user@innolive.studio/en/privacy",
        ).forEach { assertFalse(it, isAccountConsentPolicyUrl(it)) }
    }
}
