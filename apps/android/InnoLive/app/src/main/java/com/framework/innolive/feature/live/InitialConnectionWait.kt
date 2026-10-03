package com.framework.innolive.feature.live

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal const val INITIAL_SIGNALING_TIMEOUT_MILLIS = 30_000L

internal data class InitialConnectionWaitState(
    val generation: Long,
    val connection: WebRtcConnectionState,
    val signalingStarted: Boolean,
)

/** Let the connection's signaling timer run for its full window after HTTP setup completes. */
internal suspend fun awaitInitialConnection(
    states: Flow<InitialConnectionWaitState>,
    generation: Long,
    setupTimeoutMillis: Long,
    signalingTimeoutMillis: Long,
) {
    val first = withTimeout(setupTimeoutMillis) {
        states.first {
            it.generation != generation ||
                it.connection != WebRtcConnectionState.CONNECTING || it.signalingStarted
        }
    }
    if (first.generation != generation || first.connection != WebRtcConnectionState.CONNECTING) return

    withTimeout(signalingTimeoutMillis) {
        states.first {
            it.generation != generation || it.connection != WebRtcConnectionState.CONNECTING
        }
    }
}
