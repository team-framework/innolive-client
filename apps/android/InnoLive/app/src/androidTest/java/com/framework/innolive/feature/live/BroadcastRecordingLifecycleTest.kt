package com.framework.innolive.feature.live

import android.Manifest
import android.media.AudioDeviceInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.consent.BroadcastConsentRule
import com.framework.innolive.feature.live.recording.BroadcastRecordingControl
import com.framework.innolive.feature.live.recording.RecordingMediaBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.webrtc.VideoTrack

/** ViewModel이 연결 실패·트랙 교체·close()·재연결에서 녹화를 어떻게 끝내는지 가짜 송출 경로와 가짜 녹화로 확인합니다. */
class BroadcastRecordingLifecycleTest {
    @get:Rule val broadcastConsents = BroadcastConsentRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun grantPermissions() {
        compose.setContent {}
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
    }

    private class FakeRecording : BroadcastRecordingControl {
        val calls = mutableListOf<String>()
        override fun begin(media: RecordingMediaBindings): Boolean { calls += "begin"; return true }
        override fun confirm() { calls += "confirm" }
        override fun remoteTrackChanged(track: VideoTrack?) { calls += "remote:${track == null}" }
        override fun finish(save: Boolean) { calls += if (save) "save" else "discard" }
    }

    private class FakeTransport(val request: LiveBroadcastTransportRequest) : LiveBroadcastTransport {
        override fun start() {
            request.callbacks.onInitialSignalingStarted()
            request.callbacks.onStateChanged(WebRtcConnectionState.CONNECTED, null)
        }
        override fun close(onComplete: (() -> Unit)?) { onComplete?.invoke() }
        override fun setPreferredAudioInput(audioInput: AudioDeviceInfo?) = Unit
        override fun setAnonymizationEnabled(enabled: Boolean, onComplete: (AnonymizationState?, AnonymizationFailure?) -> Unit) = Unit
        override fun saveBroadcastSettings(settings: BroadcastSettings) = Unit
        override fun prepareBroadcast(settings: BroadcastSettings, allowConcurrent: Boolean): Boolean {
            request.callbacks.onBroadcastStateChanged(BroadcastState.PREPARED, null)
            return true
        }
        override fun prepareBroadcast(chzzkSettings: ChzzkBroadcastSettings): Boolean = false
        override fun goLive(onAccepted: () -> Unit): Boolean {
            onAccepted()
            request.callbacks.onBroadcastStateChanged(BroadcastState.GOING_LIVE, null)
            request.callbacks.onBroadcastStateChanged(BroadcastState.LIVE, null)
            return true
        }
        override fun pauseBroadcast() = Unit
        override fun resumeBroadcast() = Unit
        override fun stopBroadcast() = Unit
    }

    private val settings = BroadcastSettings("녹화 검증", "설명", "private", false, "22")

    private fun liveSession(recording: FakeRecording, transports: MutableList<FakeTransport>): WebRtcSessionViewModel {
        lateinit var session: WebRtcSessionViewModel
        compose.runOnIdle {
            session = WebRtcSessionViewModel(
                { request -> FakeTransport(request).also(transports::add) },
                { _, _ -> recording },
            )
            assertTrue(session.prepareBroadcast(compose.activity, settings) { "access-token" })
        }
        compose.waitUntil(10_000) { session.broadcastState == BroadcastState.PREPARED }
        compose.runOnIdle { assertTrue(session.goLive(0, 0) {}) }
        compose.waitUntil(10_000) { session.broadcastState == BroadcastState.LIVE }
        return session
    }

    @Test fun connectionFailureSavesTheConfirmedRecordingImmediately() {
        val recording = FakeRecording()
        val transports = mutableListOf<FakeTransport>()
        val session = liveSession(recording, transports)
        compose.runOnIdle {
            // 실패한 연결은 방송 상태 콜백을 더 보내지 않으므로 이 시점에 저장해야 합니다.
            transports.single().request.callbacks.onStateChanged(WebRtcConnectionState.FAILED, ConnectionFailure.GENERIC)
        }
        compose.waitUntil(10_000) { "save" in recording.calls }
        compose.runOnIdle {
            assertEquals(BroadcastState.FAILED, session.broadcastState)
            assertEquals(listOf("begin", "confirm", "save"), recording.calls)
            // 다시 연결해도 같은 녹화를 두 번 끝내지 않습니다.
            session.start(compose.activity) { "access-token" }
        }
        compose.waitUntil(10_000) { transports.size == 2 }
        compose.runOnIdle {
            assertEquals(listOf("begin", "confirm", "save"), recording.calls)
            session.close()
        }
    }

    @Test fun remoteTrackReplacementMovesTheProcessedInput() {
        val recording = FakeRecording()
        val transports = mutableListOf<FakeTransport>()
        val session = liveSession(recording, transports)
        compose.runOnIdle { transports.single().request.callbacks.onRemoteTrackChanged(null) }
        compose.runOnIdle {
            assertTrue("remote:true" in recording.calls)
            session.close()
        }
    }

    @Test fun closeDuringBroadcastSavesTheRecording() {
        val recording = FakeRecording()
        val session = liveSession(recording, mutableListOf())
        compose.runOnIdle {
            session.close()
            assertEquals(listOf("begin", "confirm", "save"), recording.calls)
            session.close()
            assertEquals(1, recording.calls.count { it == "save" })
        }
    }
}
