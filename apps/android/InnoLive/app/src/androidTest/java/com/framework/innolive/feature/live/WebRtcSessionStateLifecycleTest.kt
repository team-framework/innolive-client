package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WebRtcSessionStateLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun duplicateStartAndLateRefreshCannotReviveClosedSession() {
        lateinit var session: WebRtcSessionViewModel
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            session.start(compose.activity) {
                calls++
                withContext(NonCancellable) { pending.await() }
                "unused-test-token"
            }
            session.start(compose.activity) { calls++; "must-not-be-used" }
            assertEquals(1, calls)
            assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
            assertEquals(AnonymizationState.UNKNOWN, session.anonymizationState)
            session.close()
            pending.complete(Unit)
        }
        compose.runOnIdle {
            assertEquals(WebRtcConnectionState.IDLE, session.connectionState)
            assertEquals(AnonymizationState.UNKNOWN, session.anonymizationState)
            assertNull(session.frameAnalyzer)
            assertNull(session.remoteVideoTrack)
            assertEquals(BroadcastState.IDLE, session.broadcastState)
        }
    }

    @Test fun failedRefreshCanRetryAndOldFailureCannotOverwriteRetry() {
        lateinit var session: WebRtcSessionViewModel
        val oldRefresh = CompletableDeferred<Unit>()
        val nextRefresh = CompletableDeferred<Unit>()
        compose.runOnIdle {
            session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java]
            session.start(compose.activity) { error("첫 인증 실패") }
        }
        compose.runOnIdle {
            assertEquals(WebRtcConnectionState.FAILED, session.connectionState)
            assertEquals(AnonymizationState.UNKNOWN, session.anonymizationState)
            session.start(compose.activity) {
                withContext(NonCancellable) { oldRefresh.await() }
                error("이전 요청 실패")
            }
            session.close()
            session.start(compose.activity) {
                nextRefresh.await()
                error("현재 요청 실패")
            }
            oldRefresh.complete(Unit)
        }
        compose.runOnIdle {
            assertEquals(WebRtcConnectionState.CONNECTING, session.connectionState)
            assertEquals("인증 토큰 갱신 중", session.connectionStatus)
            nextRefresh.complete(Unit)
        }
        compose.runOnIdle {
            assertEquals(WebRtcConnectionState.FAILED, session.connectionState)
            assertEquals("현재 요청 실패", session.connectionStatus)
            assertEquals(AnonymizationState.UNKNOWN, session.anonymizationState)
            session.close()
        }
    }
}
