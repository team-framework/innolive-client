package com.framework.innolive.feature.live

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InitialConnectionWaitTest {
    @Test fun slowSetupStillGetsFullSignalingWindow() = runBlocking {
        val states = MutableStateFlow(
            InitialConnectionWaitState(1, WebRtcConnectionState.CONNECTING, false),
        )
        val updates = launch {
            delay(600)
            states.value = states.value.copy(signalingStarted = true)
            delay(600)
            states.value = states.value.copy(connection = WebRtcConnectionState.CONNECTED)
        }

        // 처음부터 1초 기한 하나만 두면 1.2초가 걸리는 이 흐름은 실패합니다.
        awaitInitialConnection(states, 1, setupTimeoutMillis = 1_000, signalingTimeoutMillis = 1_000)
        assertEquals(WebRtcConnectionState.CONNECTED, states.value.connection)
        updates.join()
    }

    @Test fun setupThatNeverStartsSignalingTimesOut() = runBlocking {
        val states = MutableStateFlow(
            InitialConnectionWaitState(1, WebRtcConnectionState.CONNECTING, false),
        )

        try {
            awaitInitialConnection(states, 1, setupTimeoutMillis = 100, signalingTimeoutMillis = 1_000)
            fail("Expected setup timeout")
        } catch (_: TimeoutCancellationException) {
            // HTTP나 네이티브 설정이 멈춰도 무한히 기다리면 안 됩니다.
        }
    }

    @Test fun signalingThatNeverConnectsTimesOutAfterStarting() = runBlocking {
        val states = MutableStateFlow(
            InitialConnectionWaitState(1, WebRtcConnectionState.CONNECTING, true),
        )

        try {
            awaitInitialConnection(states, 1, setupTimeoutMillis = 1_000, signalingTimeoutMillis = 100)
            fail("Expected signaling timeout")
        } catch (_: TimeoutCancellationException) {
            // 두 번째 기한은 시그널링이 시작된 뒤부터 셉니다.
        }
    }
}
