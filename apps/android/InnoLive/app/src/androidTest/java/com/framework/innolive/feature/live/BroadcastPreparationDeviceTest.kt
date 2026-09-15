package com.framework.innolive.feature.live

import android.Manifest
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.login.oauth.google.AuthenticationSessionViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

// 명시적 실행 인자가 있을 때만 실제 계정으로 비공개 방송을 준비하고 취소합니다.
class BroadcastPreparationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun offPreparationConnectsAutomaticallyAndCanPrepareAgainAfterCancellation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBroadcastPreparation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
        val preference = AnonymizationPreference(compose.activity)
        val previous = preference.enabled
        lateinit var session: WebRtcSessionViewModel
        lateinit var auth: AuthenticationSessionViewModel
        val settings = BroadcastSettings("InnoLive 방송 준비 검증", "비공개 준비 및 취소 검증", "private", false, "22")
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            auth = ViewModelProvider(compose.activity)[AuthenticationSessionViewModel::class.java]
            session.selectInitialAnonymization(compose.activity, false)
        }
        try {
            compose.setContent {
                CameraPreview(CameraLensFacing.BACK, null, frameAnalyzer = session.frameAnalyzer)
            }
            compose.runOnIdle {
                assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
                assertTrue(session.prepareBroadcast(compose.activity, settings, auth::refreshAccessToken))
                assertFalse(session.prepareBroadcast(compose.activity, settings, auth::refreshAccessToken))
            }
            waitForPrepared(session)
            val originalTrack = session.remoteVideoTrack
            for (attempt in 1..2) {
                compose.runOnIdle {
                    assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                    assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                    assertNotNull(session.remoteVideoTrack)
                    assertSame(originalTrack, session.remoteVideoTrack)
                    Log.i("BroadcastPreparationDeviceTest", "비공개 방송 준비 $attempt 성공: Off 및 연결 유지")
                    session.stopBroadcast()
                }
                compose.waitUntil(25_000) { session.broadcastState in setOf(BroadcastState.IDLE, BroadcastState.FAILED) }
                compose.runOnIdle {
                    assertEquals(session.broadcastStatus, BroadcastState.IDLE, session.broadcastState)
                    assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                    if (attempt == 1) {
                        assertTrue(session.prepareBroadcast(compose.activity, settings, auth::refreshAccessToken))
                    }
                }
                if (attempt == 1) waitForPrepared(session)
            }
        } finally {
            compose.runOnIdle { session.close(); preference.enabled = previous }
        }
    }

    private fun waitForPrepared(session: WebRtcSessionViewModel) {
        compose.waitUntil(65_000) { session.broadcastState in setOf(BroadcastState.PREPARED, BroadcastState.FAILED) }
        compose.runOnIdle { assertEquals(session.broadcastStatus, BroadcastState.PREPARED, session.broadcastState) }
    }
}
