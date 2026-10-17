package com.framework.innolive.feature.live.recording

import com.framework.innolive.feature.live.BroadcastState

/**
 * 확인된 방송 구간과 녹화 하나를 묶습니다(iOS `BroadcastRecordingCoordinator`와 같은 정책).
 *
 * - 방송 시작 요청 직전에 녹화를 시작하고, 라이브·일시 중지를 확인하면 저장 대상으로 봅니다.
 * - 시작을 확인하지 못한 채 방송 상태가 끝나면 녹화를 버립니다. 확인 뒤 끝나면(종료·연결 실패 포함) 저장합니다.
 * - 녹화 오류는 방송 상태를 바꾸지 않습니다.
 */
internal class BroadcastRecordingCoordinator(private val control: Control) {
    interface Control {
        /** 녹화를 시작합니다. false면 이번 방송은 녹화하지 않습니다. */
        fun begin(): Boolean

        /** 녹화를 끝냅니다. [save]가 false면 파일을 버립니다. */
        fun finish(save: Boolean)
    }

    enum class Phase { IDLE, ARMED, CONFIRMED }

    var phase = Phase.IDLE
        private set

    /** 방송 시작 요청을 보내기 직전에 부릅니다. */
    fun onGoLiveRequested() {
        if (phase == Phase.IDLE && control.begin()) phase = Phase.ARMED
    }

    /** 방송 시작 요청이 바로 거절됐을 때 부릅니다. */
    fun onGoLiveRejected() {
        if (phase == Phase.ARMED) end(save = false)
    }

    fun onBroadcastState(state: BroadcastState) {
        when (phase) {
            Phase.IDLE -> Unit
            Phase.ARMED -> when {
                state == BroadcastState.LIVE || state == BroadcastState.PAUSED -> phase = Phase.CONFIRMED
                state !in ACTIVE_STATES -> end(save = false)
            }
            Phase.CONFIRMED -> if (state !in ACTIVE_STATES) end(save = true)
        }
    }

    /** 송출 경로를 닫거나 바꿀 때 부릅니다. 확인된 방송은 원격 종료 확인 없이도 저장합니다. */
    fun onConnectionClosed() {
        when (phase) {
            Phase.IDLE -> Unit
            Phase.ARMED -> end(save = false)
            Phase.CONFIRMED -> end(save = true)
        }
    }

    private fun end(save: Boolean) {
        phase = Phase.IDLE
        control.finish(save)
    }

    companion object {
        /** 방송 시작 요청부터 종료 확인 전까지 녹화를 유지하는 상태입니다. */
        val ACTIVE_STATES = setOf(
            BroadcastState.GOING_LIVE,
            BroadcastState.LIVE,
            BroadcastState.PAUSED,
            BroadcastState.PAUSING,
            BroadcastState.RESUMING,
            BroadcastState.STOPPING,
        )
    }
}
