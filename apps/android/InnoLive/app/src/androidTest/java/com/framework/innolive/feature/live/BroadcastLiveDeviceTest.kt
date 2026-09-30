package com.framework.innolive.feature.live

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.login.oauth.google.AuthenticationSessionViewModel
import com.framework.innolive.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
        val originalOrientation = compose.activity.requestedOrientation
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
            assertServerSnapshot(session, auth)
            compose.waitUntil(15_000) { session.remoteVideoTrack != null }
            val originalTrack = session.remoteVideoTrack
            compose.runOnIdle {
                assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                val activity = compose.activity
                val rotation = checkNotNull(activity.display).rotation
                val orientation = screenOrientationFor(
                    rotation,
                    activity.resources.configuration.orientation,
                )
                assertTrue(session.goLive(rotation, orientation) {
                    activity.requestedOrientation = orientation
                })
            }
            awaitBroadcast(session, BroadcastState.LIVE)
            assertServerSnapshot(session, auth)
            // 앱 밖에서 바뀐 서버 상태가 주기 조회를 통해 반영되는지 확인합니다.
            withServerResponse(session, auth, "/stream/pause") { assertEquals(200, it.first) }
            awaitBroadcast(session, BroadcastState.PAUSED)
            assertServerSnapshot(session, auth)
            compose.runOnIdle { session.resumeBroadcast() }
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
            assertServerSnapshot(session, auth)
            compose.runOnIdle {
                assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                assertSame(originalTrack, session.remoteVideoTrack)
                session.close()
                session.start(compose.activity, auth::refreshAccessToken)
            }
            compose.waitUntil(50_000) { session.connectionState != WebRtcConnectionState.CONNECTING }
            compose.runOnIdle {
                assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                assertEquals(AnonymizationState.DISABLED, session.anonymizationState)
                assertFalse(preference.enabled)
                assertEquals(BroadcastState.IDLE, session.broadcastState)
            }
        } finally {
            try {
                if (session.broadcastState in setOf(BroadcastState.LIVE, BroadcastState.PAUSED, BroadcastState.PREPARED)) {
                    compose.runOnIdle { session.stopBroadcast() }
                    awaitBroadcast(session, BroadcastState.IDLE)
                }
            } finally {
                compose.runOnIdle {
                    session.close()
                    preference.enabled = originalSelection
                    compose.activity.requestedOrientation = originalOrientation
                }
            }
        }
    }

    private fun awaitBroadcast(session: WebRtcSessionViewModel, expected: BroadcastState) {
        compose.waitUntil(70_000) { session.broadcastState == expected || session.broadcastState == BroadcastState.FAILED }
        compose.runOnIdle { assertEquals(expected, session.broadcastState) }
    }

    private fun assertServerSnapshot(session: WebRtcSessionViewModel, auth: AuthenticationSessionViewModel) {
        withServerResponse(session, auth) { (code, payload) ->
            assertEquals("세션 조회", 200, code)
            val response = JSONObject(payload)
            val snapshot = requireNotNull(session.sessionSnapshot)
            assertEquals(response.getString("session_id"), snapshot.sessionId)
            assertEquals(response.getString("provider"), snapshot.provider)
            val targets = response.optJSONArray("targets")
            if (targets != null) {
                assertEquals(targets.length(), snapshot.targets?.size)
                repeat(targets.length()) { index ->
                    val item = targets.getJSONObject(index)
                    val stream = item.getJSONObject("stream")
                    val target = requireNotNull(snapshot.targets)[index]
                    assertEquals(item.getString("provider"), target.provider)
                    assertEquals(stream.getString("status"), target.status)
                    assertEquals(stream.getString("broadcast_phase"), target.broadcastPhase)
                    assertEquals(stream.opt("stop_reason").takeUnless { it == JSONObject.NULL }, target.stopReason)
                    assertEquals(stream.optLong("reconnect_attempts"), target.reconnectAttempts)
                }
            } else {
                assertEquals(response.getJSONObject("stream").getString("broadcast_phase"),
                    snapshot.targets?.firstOrNull { it.provider == snapshot.provider }?.broadcastPhase)
            }
            val notices = response.optJSONArray("notices")
            assertEquals(notices?.length() ?: 0, snapshot.notices?.size ?: 0)
            if (notices != null) repeat(notices.length()) { index ->
                val notice = notices.getJSONObject(index)
                assertEquals(notice.getString("code"), snapshot.notices?.get(index)?.code)
                assertEquals(notice.opt("at").takeUnless { it == JSONObject.NULL }, snapshot.notices?.get(index)?.at)
            }
            assertTrue("남은 시간 필드 필요", response.has("broadcast_remaining_seconds"))
            if (response.isNull("broadcast_remaining_seconds")) {
                assertEquals(BroadcastRemainingTime.UnlimitedOrInactive, snapshot.remainingTime)
            } else {
                val remaining = snapshot.remainingTime as BroadcastRemainingTime.Seconds
                assertTrue("주기 조회 시간 오차", kotlin.math.abs(remaining.value - response.getLong("broadcast_remaining_seconds")) <= 10)
            }
        }
    }

    private fun withServerResponse(
        session: WebRtcSessionViewModel,
        auth: AuthenticationSessionViewModel,
        path: String = "",
        verify: (Pair<Int, String>) -> Unit,
    ) {
        val connectionField = session.javaClass.getDeclaredField("connection").apply { isAccessible = true }
        val connection = requireNotNull(connectionField.get(session)) as WebRtcConnection
        val sessionField = connection.javaClass.getDeclaredField("session").apply { isAccessible = true }
        val created = requireNotNull(sessionField.get(connection)) as CreatedSession
        val client = OkHttpClient()
        try {
            val builder = Request.Builder()
                .url(BuildConfig.INNOLIVE_SERVER_URL.trimEnd('/') + "/sessions/" + created.sessionId + path)
                .header("Authorization", "Bearer ${requireNotNull(auth.session.value).accessToken}")
                .header("X-Session-Owner-Token", created.ownerToken)
            if (path.isNotEmpty()) builder.post(ByteArray(0).toRequestBody())
            client.newCall(builder.build()).execute().use { verify(it.code to it.body.string()) }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
