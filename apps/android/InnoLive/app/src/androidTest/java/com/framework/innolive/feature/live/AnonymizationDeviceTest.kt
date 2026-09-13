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
        val preference = AnonymizationPreference(compose.activity)
        val previousSelection = preference.enabled
        lateinit var session: WebRtcSessionViewModel
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            assertFalse(session.setAnonymizationEnabled(false))
            assertTrue(session.selectInitialAnonymization(compose.activity, false))
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
            compose.runOnIdle {
                assertEquals(session.connectionStatus, WebRtcConnectionState.CONNECTED, session.connectionState)
                assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                assertFalse(session.selectInitialAnonymization(compose.activity, true))
                Log.i("AnonymizationDeviceTest", "초기 Off 확인 후 WebRTC 연결 성공")
            }
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
            // 새 세션에서도 마지막으로 성공한 Off를 다시 적용합니다.
            compose.runOnIdle {
                session.close()
                val auth = ViewModelProvider(compose.activity)[AuthenticationSessionViewModel::class.java]
                session.start(compose.activity, auth::refreshAccessToken)
            }
            compose.waitUntil(45_000) { session.connectionState == WebRtcConnectionState.CONNECTED || session.connectionState == WebRtcConnectionState.FAILED }
            compose.runOnIdle {
                assertEquals(session.connectionStatus, WebRtcConnectionState.CONNECTED, session.connectionState)
                assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                assertFalse(session.selectedAnonymizationEnabled)
                Log.i("AnonymizationDeviceTest", "재연결 새 세션 Off 복원 확인")
                session.close()
                assertTrue(session.selectInitialAnonymization(compose.activity, true))
                val auth = ViewModelProvider(compose.activity)[AuthenticationSessionViewModel::class.java]
                session.start(compose.activity, auth::refreshAccessToken)
            }
            compose.waitUntil(45_000) { session.connectionState == WebRtcConnectionState.CONNECTED || session.connectionState == WebRtcConnectionState.FAILED }
            compose.runOnIdle {
                assertEquals(session.connectionStatus, WebRtcConnectionState.CONNECTED, session.connectionState)
                assertEquals(AnonymizationState.ENABLED, session.anonymizationState)
                Log.i("AnonymizationDeviceTest", "초기 On 확인 후 WebRTC 연결 성공")
                assertTrue(session.setAnonymizationEnabled(false))
                session.close()
                assertEquals(AnonymizationState.UNKNOWN, session.anonymizationState)
                assertEquals(AnonymizationChangeStatus.IDLE, session.anonymizationChange.status)
            }
            compose.runOnIdle { assertFalse(session.setAnonymizationEnabled(false)) }
        } finally {
            compose.runOnIdle { session.close(); preference.enabled = previousSelection }
        }
    }
}
