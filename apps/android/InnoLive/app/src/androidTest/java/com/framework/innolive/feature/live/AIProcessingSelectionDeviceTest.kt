package com.framework.innolive.feature.live

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
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

    @Test fun broadcastPreparationAfterModeSelectionStartsReplacementConnection() {
        compose.setContent {}
        val context = compose.activity
        val preference = AIProcessingPreference(context)
        val original = preference.onDevice
        val session = WebRtcSessionViewModel()
        val refresh = CompletableDeferred<Unit>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        try {
            compose.runOnIdle {
                @Suppress("UNCHECKED_CAST")
                val state = WebRtcSessionViewModel::class.java
                    .getDeclaredField("sessionState\$delegate")
                    .apply { isAccessible = true }
                    .get(session) as MutableState<WebRtcSessionState>
                state.value = WebRtcSessionState(connection = WebRtcConnectionState.CONNECTED)

                assertTrue(session.selectAIProcessing(context, !original))
                assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
                assertTrue(session.prepareBroadcast(
                    context,
                    BroadcastSettings("모드 전환 검증", "방송 준비 검증", "private", false, "22"),
                ) {
                    refresh.await()
                    error("검증용 인증 실패")
                })
                assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
                refresh.complete(Unit)
            }
            compose.waitUntil(10_000) { !session.isPreparingBroadcast }
            compose.runOnIdle {
                assertEquals(WebRtcConnectionState.FAILED, session.connectionState)
                assertEquals(BroadcastState.FAILED, session.broadcastState)
            }
        } finally {
            compose.runOnIdle { session.close(); preference.onDevice = original }
        }
    }
}
