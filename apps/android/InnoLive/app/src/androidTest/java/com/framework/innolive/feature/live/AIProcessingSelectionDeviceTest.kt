package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AIProcessingSelectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun choosingOnDeviceFromSettingsDefersConnectionUntilCameraReturns() {
        val preference = AIProcessingPreference(compose.activity)
        val original = preference.onDevice
        val session = WebRtcSessionViewModel()
        try {
            compose.runOnIdle {
                @Suppress("UNCHECKED_CAST")
                val state = WebRtcSessionViewModel::class.java
                    .getDeclaredField("sessionState\$delegate")
                    .apply { isAccessible = true }
                    .get(session) as MutableState<WebRtcSessionState>
                state.value = WebRtcSessionState(connection = WebRtcConnectionState.CONNECTED)

                assertTrue(session.canChangeAIProcessing)
                assertTrue(session.selectAIProcessing(compose.activity, true))
                assertTrue(AIProcessingPreference(compose.activity).onDevice)
                // Settings has no CameraPreview. Starting here would wait for a protected frame
                // that cannot arrive and turn the selection into a 30-second connection failure.
                assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
            }
        } finally {
            compose.runOnIdle { session.close(); preference.onDevice = original }
        }
    }
}
