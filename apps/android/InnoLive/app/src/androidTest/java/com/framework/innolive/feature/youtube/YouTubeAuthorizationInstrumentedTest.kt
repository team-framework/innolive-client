package com.framework.innolive.feature.youtube

import com.google.android.gms.auth.api.identity.AuthorizationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthorizationInstrumentedTest {
    @Test
    fun offlineAuthorizationRequestsFreshConsentAndAccountSelection() {
        val request = buildYouTubeAuthorizationRequest(
            YouTubeConfiguration(
                webClientId = "web-client-id",
                scope = "youtube-scope",
            ),
        )

        assertTrue(request.isOfflineAccessRequested)
        assertEquals("web-client-id", request.serverClientId)
        assertEquals(
            AuthorizationRequest.Prompt.CONSENT or AuthorizationRequest.Prompt.SELECT_ACCOUNT,
            request.prompt,
        )
        assertNull(request.account)
        assertEquals(listOf("youtube-scope"), request.requestedScopes.map { it.scopeUri })
    }
}
