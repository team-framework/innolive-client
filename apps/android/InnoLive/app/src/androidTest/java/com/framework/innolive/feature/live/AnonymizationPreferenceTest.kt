package com.framework.innolive.feature.live

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AnonymizationPreferenceTest {
    @Test fun selectionSurvivesNewPreferenceInstanceWithoutStoringServerState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preference = AnonymizationPreference(context)
        val previous = preference.enabled
        try {
            for (enabled in listOf(false, true)) {
                preference.enabled = enabled
                assertEquals(enabled, AnonymizationPreference(context).enabled)
                assertEquals(AnonymizationState.UNKNOWN, WebRtcSessionState().anonymization)
            }
        } finally {
            preference.enabled = previous
        }
    }
}
