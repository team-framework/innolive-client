package com.framework.innolive.feature.live

import android.Manifest
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BroadcastPreparationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun grantPermissions() {
        // 실제 앱처럼 Compose의 상태 변경 알림을 시작합니다.
        compose.setContent {}
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
    }
    private val settings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22")

    @Test fun invalidSettingsDoNotStartConnection() {
        compose.runOnIdle {
            val session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            assertFalse(session.prepareBroadcast(compose.activity, settings.copy(madeForKids = null)) {
                error("설정 확인 전 인증 요청을 보내면 안 됩니다.")
            })
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
            assertFalse(session.isPreparingBroadcast)
            assertEquals(BroadcastState.FAILED, session.broadcastState)
        }
    }

    @Test fun oneConfirmationStartsConnectionAndFailureCanRetry() {
        lateinit var session: WebRtcSessionViewModel
        var calls = 0
        val refresh = CompletableDeferred<Unit>()
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            assertTrue(session.prepareBroadcast(compose.activity, settings) {
                calls++
                refresh.await()
                error("검증용 인증 실패")
            })
            assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
            assertTrue(session.isPreparingBroadcast)
            assertFalse(session.prepareBroadcast(compose.activity, settings) { calls++; "unused" })
            assertEquals(1, calls)
            refresh.complete(Unit)
        }
        compose.waitUntil(10_000) { !session.isPreparingBroadcast }
        compose.runOnIdle {
            assertEquals(WebRtcConnectionState.FAILED, session.connectionState)
            assertEquals(BroadcastState.FAILED, session.broadcastState)
            assertTrue(session.prepareBroadcast(compose.activity, settings) {
                calls++
                error("재시도 인증 실패")
            })
        }
        compose.waitUntil(10_000) { !session.isPreparingBroadcast }
        compose.runOnIdle {
            assertEquals(2, calls)
            assertEquals(BroadcastState.FAILED, session.broadcastState)
        }
    }

    @Test fun closingWhileConnectingCancelsPreparationAndIgnoresLateAuthentication() {
        lateinit var session: WebRtcSessionViewModel
        val refresh = CompletableDeferred<Unit>()
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            session.prepareBroadcast(compose.activity, settings) {
                withContext(NonCancellable) { refresh.await() }
                "unused-test-token"
            }
            session.close()
            refresh.complete(Unit)
        }
        compose.runOnIdle {
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
            assertEquals(BroadcastState.IDLE, session.broadcastState)
            assertFalse(session.isPreparingBroadcast)
            assertNull(session.frameAnalyzer)
        }
    }

    @Test fun rejectedNativePreparationRestoresRetryableFailureState() {
        lateinit var session: WebRtcSessionViewModel
        lateinit var rejectedConnection: WebRtcConnection
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            rejectedConnection = WebRtcConnection(
                context = compose.activity,
                serverUrl = "https://example.test",
                accessToken = accessTokenFor("broadcast-preparation-user"),
                initialAnonymizationEnabled = true,
                preferredAudioInput = null,
                onStateChanged = { _, _ -> },
                onRemoteTrackChanged = {},
                onLocalMediaReady = { _, _ -> },
                onLocalMediaCleared = {},
                onBroadcastStateChanged = { _, _ -> },
                onAnonymizationStateConfirmed = {},
            ).also(WebRtcConnection::close)

            assertFalse(session.requestBroadcastPreparation(rejectedConnection, settings))
            assertEquals(BroadcastState.FAILED, session.broadcastState)
            assertEquals(UiText.Resource(R.string.error_broadcast_request), session.broadcastStatus)
            assertTrue(session.broadcastState.canPrepare)
        }
    }

    private companion object {
        fun accessTokenFor(user: String): String {
            val payload = Base64.encodeToString(
                "{\"sub\":\"$user\"}".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            return "header.$payload.signature"
        }
    }
}
