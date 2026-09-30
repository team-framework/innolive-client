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

        // A single 1-second deadline from the beginning would fail this 1.2-second flow.
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
            // An HTTP or native setup stall must not wait forever.
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
            // The second deadline starts after signaling begins.
        }
    }
}
