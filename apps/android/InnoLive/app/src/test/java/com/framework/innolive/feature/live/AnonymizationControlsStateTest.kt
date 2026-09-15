package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test

class AnonymizationControlsStateTest {
    private fun state(
        connection: WebRtcConnectionState = WebRtcConnectionState.CONNECTED,
        confirmed: AnonymizationState = AnonymizationState.ENABLED,
        change: AnonymizationChange = AnonymizationChange(),
        broadcast: BroadcastState = BroadcastState.IDLE,
        loaded: Boolean = true,
    ) = anonymizationControlsState(connection, confirmed, false, loaded, change, broadcast)

    @Test fun disconnectedUsesSelectionButConnectedUsesOnlyServerConfirmation() {
        assertEquals(false, state(connection = WebRtcConnectionState.IDLE).selectedEnabled)
        assertEquals(true, state().selectedEnabled)
        val unknown = state(confirmed = AnonymizationState.UNKNOWN)
        assertNull(unknown.selectedEnabled)
        assertTrue(unknown.canChange)
        assertEquals("비식별화 상태 확인 필요", unknown.label)
    }

    @Test fun changingKeepsConfirmedValueAndPreventsDuplicateRequests() {
        val pending = state(change = AnonymizationChange(status = AnonymizationChangeStatus.CHANGING, requestedEnabled = false))
        assertEquals(true, pending.selectedEnabled)
        assertFalse(pending.canChange)
        assertEquals("비식별화 변경 중", pending.label)
        val failed = state(change = AnonymizationChange(status = AnonymizationChangeStatus.FAILED, errorMessage = "오류"))
        assertEquals(true, failed.selectedEnabled)
        assertTrue(failed.canChange)
    }

    @Test fun connectingAllowsCancelButRejectsSelectionAndUnloadedDisablesActions() {
        val connecting = state(connection = WebRtcConnectionState.CONNECTING)
        assertFalse(connecting.canChange)
        assertTrue(connecting.canControlConnection)
        assertEquals("연결 취소", connecting.connectionLabel)
        val unloaded = state(connection = WebRtcConnectionState.IDLE, loaded = false)
        assertFalse(unloaded.canChange)
        assertFalse(unloaded.canControlConnection)
        assertNull(unloaded.selectedEnabled)
    }

    @Test fun broadcastKeepsToggleAvailableButProtectsConnection() {
        for (broadcast in BroadcastState.entries) {
            val current = state(broadcast = broadcast)
            assertTrue(current.canChange)
            assertEquals(broadcast == BroadcastState.IDLE || broadcast == BroadcastState.FAILED, current.canControlConnection)
        }
    }
}
