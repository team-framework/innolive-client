package com.framework.innolive.feature.live

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.login.oauth.google.AuthenticationSessionViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

// 실제 영상·마이크를 비공개 YouTube 라이브로 송출하므로 별도 실행 인자가 필요하다.
class BroadcastLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun offLiveToggleStopAndReconnectPreserveSelection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBroadcastLifecycle") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
        val preference = AnonymizationPreference(compose.activity)
        val originalSelection = preference.enabled
        lateinit var session: WebRtcSessionViewModel
        lateinit var auth: AuthenticationSessionViewModel
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            auth = ViewModelProvider(compose.activity)[AuthenticationSessionViewModel::class.java]
            assertNotNull("Google 로그인 필요", auth.session.value)
            session.selectInitialAnonymization(compose.activity, false)
        }
        try {
            compose.setContent { CameraPreview(CameraLensFacing.BACK, null, frameAnalyzer = session.frameAnalyzer) }
            compose.runOnIdle {
                assertTrue(session.prepareBroadcast(
                    compose.activity,
                    BroadcastSettings("InnoLive 비공개 라이브 검증", "방송 상태 전환 검증", "private", false, "22"),
                    auth::refreshAccessToken,
                ))
            }
            awaitBroadcast(session, BroadcastState.PREPARED)
            compose.waitUntil(15_000) { session.remoteVideoTrack != null }
            val originalTrack = session.remoteVideoTrack
            compose.runOnIdle {
                assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                session.goLive()
            }
            awaitBroadcast(session, BroadcastState.LIVE)
            for (enabled in listOf(true, false)) {
                compose.runOnIdle {
                    assertTrue(session.setAnonymizationEnabled(enabled))
                    assertFalse(session.setAnonymizationEnabled(!enabled))
                }
                compose.waitUntil(25_000) { session.anonymizationChange.status != AnonymizationChangeStatus.CHANGING }
                compose.runOnIdle {
                    assertNull(session.anonymizationChange.errorMessage)
                    assertEquals(if (enabled) AnonymizationState.ENABLED else AnonymizationState.DISABLED, session.anonymizationState)
                    assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                    assertEquals(BroadcastState.LIVE, session.broadcastState)
                    assertSame(originalTrack, session.remoteVideoTrack)
                    assertEquals(enabled, preference.enabled)
                }
            }
            compose.runOnIdle { session.stopBroadcast() }
            awaitBroadcast(session, BroadcastState.IDLE)
            compose.runOnIdle {
                assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                assertSame(originalTrack, session.remoteVideoTrack)
                session.close()
                session.start(compose.activity, auth::refreshAccessToken)
            }
            compose.waitUntil(50_000) { session.connectionState != WebRtcConnectionState.CONNECTING }
            compose.runOnIdle {
                assertEquals(session.connectionStatus, WebRtcConnectionState.CONNECTED, session.connectionState)
                assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                assertFalse(preference.enabled)
                assertEquals(BroadcastState.IDLE, session.broadcastState)
            }
        } finally {
            try {
                if (session.broadcastState == BroadcastState.LIVE || session.broadcastState == BroadcastState.PREPARED) {
                    compose.runOnIdle { session.stopBroadcast() }
                    awaitBroadcast(session, BroadcastState.IDLE)
                }
            } finally {
                compose.runOnIdle { session.close(); preference.enabled = originalSelection }
            }
        }
    }

    private fun awaitBroadcast(session: WebRtcSessionViewModel, expected: BroadcastState) {
        compose.waitUntil(70_000) { session.broadcastState == expected || session.broadcastState == BroadcastState.FAILED }
        compose.runOnIdle { assertEquals(session.broadcastStatus, expected, session.broadcastState) }
    }
}
