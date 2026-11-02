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

/** HTTP 설정이 끝난 뒤 연결의 시그널링 타이머가 전체 대기 시간 동안 동작하게 합니다. */
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
