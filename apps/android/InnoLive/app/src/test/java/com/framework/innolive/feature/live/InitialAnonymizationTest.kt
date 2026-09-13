package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class InitialAnonymizationTest {
    @Test fun acceptsBothSettingsOnlyAfterServerConfirmation() {
        for ((enabled, state) in listOf(true to AnonymizationState.ENABLED, false to AnonymizationState.DISABLED)) {
            var requested = false
            assertEquals(state, confirmInitialAnonymization(enabled) { requested = true; state })
            assertTrue(requested)
        }
    }

    @Test fun failureUnknownAndOppositeResponsePreventContinuingToMedia() {
        for (enabled in listOf(true, false)) {
            for (response in listOf<() -> AnonymizationState>(
                { throw IOException("응답 유실") },
                { AnonymizationState.UNKNOWN },
                { if (enabled) AnonymizationState.DISABLED else AnonymizationState.ENABLED },
            )) {
                var mediaStarted = false
                assertThrows(IllegalStateException::class.java) {
                    confirmInitialAnonymization(enabled, response)
                    mediaStarted = true
                }
                assertFalse(mediaStarted)
            }
        }
    }
}
