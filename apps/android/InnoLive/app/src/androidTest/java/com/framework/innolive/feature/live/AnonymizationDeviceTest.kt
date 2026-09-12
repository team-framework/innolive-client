package com.framework.innolive.feature.live

import android.Manifest
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import com.framework.innolive.feature.login.oauth.google.AuthenticationSessionViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

// 실제 계정·서버·카메라를 사용하는 검증은 명시적 실행 인자가 있을 때만 수행합니다.
class AnonymizationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun changesOffAndOnWithoutReplacingConnectionAndRejectsDuplicate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAnonymization") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
        lateinit var session: WebRtcSessionViewModel
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            assertFalse(session.setAnonymizationEnabled(false))
        }
        try {
            compose.setContent {
                CameraPreview(
                    cameraLensFacing = CameraLensFacing.BACK,
                    cameraResolution = null,
                    frameAnalyzer = session.frameAnalyzer,
                )
            }
            compose.runOnIdle {
                val auth = ViewModelProvider(compose.activity)[AuthenticationSessionViewModel::class.java]
                session.start(compose.activity, auth::refreshAccessToken)
            }
            compose.waitUntil(45_000) { session.connectionState == WebRtcConnectionState.CONNECTED || session.connectionState == WebRtcConnectionState.FAILED }
            compose.runOnIdle { assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState) }
            compose.waitUntil(15_000) { session.remoteVideoTrack != null }
            val originalTrack = session.remoteVideoTrack
            val originalBroadcast = session.broadcastState
            for (enabled in listOf(false, true, false)) {
                compose.runOnIdle {
                    assertTrue(session.setAnonymizationEnabled(enabled))
                    assertEquals(AnonymizationChangeStatus.CHANGING, session.anonymizationChange.status)
                    assertFalse(session.setAnonymizationEnabled(!enabled))
                }
                compose.waitUntil(20_000) { session.anonymizationChange.status != AnonymizationChangeStatus.CHANGING }
                compose.runOnIdle {
                    assertEquals(session.anonymizationChange.errorMessage, AnonymizationChangeStatus.IDLE, session.anonymizationChange.status)
                    assertEquals(if (enabled) AnonymizationState.ENABLED else AnonymizationState.DISABLED, session.anonymizationState)
                    assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                    assertSame(originalTrack, session.remoteVideoTrack)
                    assertEquals(originalBroadcast, session.broadcastState)
                    Log.i("AnonymizationDeviceTest", "PATCH 확인: enabled=$enabled, WebRTC 및 원격 트랙 유지")
                }
            }
            compose.runOnIdle {
                assertTrue(session.setAnonymizationEnabled(true))
                session.close()
                assertEquals(AnonymizationState.UNKNOWN, session.anonymizationState)
                assertEquals(AnonymizationChangeStatus.IDLE, session.anonymizationChange.status)
            }
            compose.runOnIdle { assertFalse(session.setAnonymizationEnabled(false)) }
        } finally {
            compose.runOnIdle { session.close() }
        }
    }
}
