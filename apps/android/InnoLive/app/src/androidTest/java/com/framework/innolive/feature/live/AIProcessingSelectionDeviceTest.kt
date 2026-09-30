package com.framework.innolive.feature.live

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val retryRefresh = CompletableDeferred<Unit>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        try {
            preference.onDevice = false
            compose.runOnIdle {
                @Suppress("UNCHECKED_CAST")
                val state = WebRtcSessionViewModel::class.java
                    .getDeclaredField("sessionState\$delegate")
                    .apply { isAccessible = true }
                    .get(session) as MutableState<WebRtcSessionState>
                state.value = WebRtcSessionState(connection = WebRtcConnectionState.CONNECTED)

                assertTrue(session.selectAIProcessing(context, true))
                assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
                assertTrue(session.prepareBroadcast(
                    context,
                    BroadcastSettings("모드 전환 검증", "방송 준비 검증", "private", false, "22"),
                ) {
                    refresh.await()
                    error("검증용 인증 실패")
                })
                assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
                assertFalse(session.canChangeAIProcessing)
                refresh.complete(Unit)
            }
            compose.waitUntil(10_000) { !session.isPreparingBroadcast }
            compose.runOnIdle {
                assertEquals(WebRtcConnectionState.FAILED, session.connectionState)
                assertEquals(BroadcastState.FAILED, session.broadcastState)
                assertTrue("Failed preparation must allow changing AI mode", session.canChangeAIProcessing)
                assertTrue(session.selectAIProcessing(context, false))
                assertFalse(session.selectedOnDeviceProcessing)
                assertFalse(preference.onDevice)
                assertTrue("Server AI must be retryable after on-device failure", session.prepareBroadcast(
                    context,
                    BroadcastSettings("서버 AI 재시도", "방송 준비 검증", "private", false, "22"),
                ) {
                    retryRefresh.await()
                    error("검증용 재시도 인증 실패")
                })
                assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
                retryRefresh.complete(Unit)
            }
            compose.waitUntil(10_000) { !session.isPreparingBroadcast }
            compose.runOnIdle { assertEquals(BroadcastState.FAILED, session.broadcastState) }
        } finally {
            compose.runOnIdle { session.close(); preference.onDevice = original }
        }
    }
}
