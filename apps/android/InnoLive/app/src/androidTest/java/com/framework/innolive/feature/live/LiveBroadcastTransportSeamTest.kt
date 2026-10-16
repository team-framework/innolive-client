package com.framework.innolive.feature.live

import android.Manifest
import android.media.AudioDeviceInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastUiState
import com.framework.innolive.feature.live.direct.broadcast.DirectPublishFeature
import com.framework.innolive.feature.live.direct.broadcast.LocalTargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.framework.innolive.feature.live.consent.BroadcastConsentRule

/** ViewModel이 송출 경로를 팩토리로 만들고 동작을 그대로 넘기는지 가짜 송출 경로로 확인합니다. */
class LiveBroadcastTransportSeamTest {
    @get:Rule val broadcastConsents = BroadcastConsentRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun grantPermissions() {
        compose.setContent {}
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
    }

    private class FakeTransport(
        val request: LiveBroadcastTransportRequest,
        private val stopResult: BroadcastState = BroadcastState.IDLE,
    ) : LiveBroadcastTransport {
        val calls = mutableListOf<String>()

        override fun start() {
            calls += "start"
            request.callbacks.onInitialSignalingStarted()
            request.callbacks.onStateChanged(WebRtcConnectionState.CONNECTED, null)
            request.callbacks.onDirectUiStateChanged(
                DirectBroadcastUiState(targets = mapOf(BroadcastProvider.YOUTUBE to LocalTargetStatus.CONNECTING)),
            )
        }

        override fun close(onComplete: (() -> Unit)?) {
            calls += "close"
            onComplete?.invoke()
        }

        override fun setPreferredAudioInput(audioInput: AudioDeviceInfo?) = Unit

        override fun setAnonymizationEnabled(
            enabled: Boolean,
            onComplete: (AnonymizationState?, AnonymizationFailure?) -> Unit,
        ) = Unit

        override fun saveBroadcastSettings(settings: BroadcastSettings) = Unit

        override fun prepareBroadcast(settings: BroadcastSettings, allowConcurrent: Boolean): Boolean {
            calls += "prepare:${settings.title}"
            request.callbacks.onBroadcastStateChanged(BroadcastState.PREPARED, null)
            return true
        }

        override fun prepareBroadcast(chzzkSettings: ChzzkBroadcastSettings): Boolean = false

        override fun goLive(onAccepted: () -> Unit): Boolean = false

        override fun pauseBroadcast() = Unit

        override fun resumeBroadcast() = Unit

        override fun stopBroadcast() {
            calls += "stop"
            request.callbacks.onBroadcastStateChanged(BroadcastState.CANCELLING_PREPARATION, null)
            request.callbacks.onBroadcastStateChanged(stopResult, null)
        }
    }

    private val settings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22")

    @Test fun preparationGoesThroughInjectedTransportWithSelectedPlan() {
        val created = mutableListOf<FakeTransport>()
        lateinit var session: WebRtcSessionViewModel
        val plan = DirectBroadcastPlan(DirectPublishResolution.FHD, setOf(BroadcastProvider.YOUTUBE))
        compose.runOnIdle {
            session = WebRtcSessionViewModel { request -> FakeTransport(request).also(created::add) }
            assertTrue(session.selectDirectBroadcastPlan(plan))
            assertTrue(session.prepareBroadcast(compose.activity, settings) { "access-token" })
        }
        compose.waitUntil(10_000) { session.broadcastState == BroadcastState.PREPARED }
        compose.runOnIdle {
            val transport = created.single()
            assertEquals(listOf("start", "prepare:검증 방송"), transport.calls)
            assertEquals(plan, transport.request.directPlan)
            assertEquals("access-token", transport.request.accessToken)
            // 화면이 Activity를 넘겨도 송출 경로는 application context만 보관합니다.
            assertSame(compose.activity.applicationContext, transport.request.context)
            assertEquals(LocalTargetStatus.CONNECTING, session.directBroadcastUiState.targets[BroadcastProvider.YOUTUBE])
            // 준비된 방송이 있으면 구성을 바꾸지 않습니다.
            assertFalse(session.selectDirectBroadcastPlan(DirectBroadcastPlan()))
            session.close()
            assertEquals("close", transport.calls.last())
            assertEquals(DirectBroadcastUiState(), session.directBroadcastUiState)
        }
    }

    @Test fun cancellingPreparationClosesConnection() {
        val created = mutableListOf<FakeTransport>()
        lateinit var session: WebRtcSessionViewModel
        compose.runOnIdle {
            session = WebRtcSessionViewModel { request -> FakeTransport(request).also(created::add) }
            assertTrue(session.prepareBroadcast(compose.activity, settings) { "access-token" })
        }
        compose.waitUntil(10_000) { session.broadcastState == BroadcastState.PREPARED }
        compose.runOnIdle { session.stopBroadcast() }
        compose.waitUntil(10_000) { session.connectionState == WebRtcConnectionState.IDLE }
        compose.runOnIdle {
            // 취소가 끝나면 비식별화 미리보기와 서버 세션을 남기지 않도록 연결을 닫습니다.
            assertEquals(listOf("start", "prepare:검증 방송", "stop", "close"), created.single().calls)
            assertEquals(BroadcastState.IDLE, session.broadcastState)
            assertNull(session.remoteVideoTrack)
        }
    }

    @Test fun failedCancellationStillClosesConnection() {
        val created = mutableListOf<FakeTransport>()
        lateinit var session: WebRtcSessionViewModel
        compose.runOnIdle {
            session = WebRtcSessionViewModel { request ->
                FakeTransport(request, stopResult = BroadcastState.PREPARED).also(created::add)
            }
            assertTrue(session.prepareBroadcast(compose.activity, settings) { "access-token" })
        }
        compose.waitUntil(10_000) { session.broadcastState == BroadcastState.PREPARED }
        compose.runOnIdle { session.stopBroadcast() }
        compose.waitUntil(10_000) { session.connectionState == WebRtcConnectionState.IDLE }
        compose.runOnIdle {
            // 취소 요청이 실패해도 iOS처럼 연결과 서버 세션을 남기지 않습니다.
            assertEquals(listOf("start", "prepare:검증 방송", "stop", "close"), created.single().calls)
            assertEquals(BroadcastState.IDLE, session.broadcastState)
            assertNull(session.remoteVideoTrack)
        }
    }

    @Test fun directPublishIsEnabled() {
        assertTrue(DirectPublishFeature.ENABLED)
    }
}
