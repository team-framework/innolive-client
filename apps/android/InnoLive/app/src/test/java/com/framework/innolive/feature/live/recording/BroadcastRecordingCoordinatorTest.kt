package com.framework.innolive.feature.live.recording

import com.framework.innolive.feature.live.BroadcastState
import org.junit.Assert.assertEquals
import org.junit.Test

class BroadcastRecordingCoordinatorTest {
    private class FakeControl(var beginResult: Boolean = true) : BroadcastRecordingCoordinator.Control {
        val calls = mutableListOf<String>()
        override fun begin(): Boolean { calls += "begin"; return beginResult }
        override fun confirm() { calls += "confirm" }
        override fun finish(save: Boolean) { calls += if (save) "save" else "discard" }
    }

    @Test fun confirmedBroadcastIsSavedWhenItEnds() {
        val control = FakeControl()
        val coordinator = BroadcastRecordingCoordinator(control)
        coordinator.onGoLiveRequested()
        coordinator.onBroadcastState(BroadcastState.GOING_LIVE)
        coordinator.onBroadcastState(BroadcastState.LIVE)
        coordinator.onBroadcastState(BroadcastState.PAUSING)
        coordinator.onBroadcastState(BroadcastState.PAUSED)
        coordinator.onBroadcastState(BroadcastState.RESUMING)
        coordinator.onBroadcastState(BroadcastState.LIVE)
        coordinator.onBroadcastState(BroadcastState.STOPPING)
        // 확인은 한 번만 알린다. 이 뒤로 앱이 종료되면 녹화를 복구한다.
        assertEquals(listOf("begin", "confirm"), control.calls)
        coordinator.onBroadcastState(BroadcastState.IDLE)
        assertEquals(listOf("begin", "confirm", "save"), control.calls)
        assertEquals(BroadcastRecordingCoordinator.Phase.IDLE, coordinator.phase)
    }

    @Test fun unconfirmedStartIsDiscarded() {
        val control = FakeControl()
        val coordinator = BroadcastRecordingCoordinator(control)
        coordinator.onGoLiveRequested()
        coordinator.onBroadcastState(BroadcastState.GOING_LIVE)
        coordinator.onBroadcastState(BroadcastState.PREPARED)
        assertEquals(listOf("begin", "discard"), control.calls)

        val rejected = FakeControl()
        val rejecting = BroadcastRecordingCoordinator(rejected)
        rejecting.onGoLiveRequested()
        rejecting.onGoLiveRejected()
        assertEquals(listOf("begin", "discard"), rejected.calls)
    }

    @Test fun connectionCloseSavesOnlyConfirmedRecordings() {
        val armed = FakeControl()
        BroadcastRecordingCoordinator(armed).apply {
            onGoLiveRequested()
            onConnectionClosed()
        }
        assertEquals(listOf("begin", "discard"), armed.calls)

        val confirmed = FakeControl()
        BroadcastRecordingCoordinator(confirmed).apply {
            onGoLiveRequested()
            onBroadcastState(BroadcastState.LIVE)
            onConnectionClosed()
            // 같은 녹화를 두 번 끝내지 않는다.
            onBroadcastState(BroadcastState.IDLE)
            onConnectionClosed()
        }
        assertEquals(listOf("begin", "confirm", "save"), confirmed.calls)

        val failed = FakeControl()
        BroadcastRecordingCoordinator(failed).apply {
            onGoLiveRequested()
            onBroadcastState(BroadcastState.LIVE)
            onBroadcastState(BroadcastState.FAILED)
        }
        assertEquals(listOf("begin", "confirm", "save"), failed.calls)
    }

    @Test fun declinedBeginNeverFinishesAndStatesWithoutRecordingAreIgnored() {
        val control = FakeControl(beginResult = false)
        val coordinator = BroadcastRecordingCoordinator(control)
        coordinator.onBroadcastState(BroadcastState.LIVE)
        coordinator.onGoLiveRequested()
        coordinator.onBroadcastState(BroadcastState.LIVE)
        coordinator.onBroadcastState(BroadcastState.IDLE)
        coordinator.onConnectionClosed()
        assertEquals(listOf("begin"), control.calls)
        // 다음 방송은 다시 시작할 수 있다.
        control.beginResult = true
        coordinator.onGoLiveRequested()
        assertEquals(BroadcastRecordingCoordinator.Phase.ARMED, coordinator.phase)
    }
}
