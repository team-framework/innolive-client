package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test

class AnonymizationControlsStateTest {
    private fun state(
        connection: WebRtcConnectionState = WebRtcConnectionState.CONNECTED,
        confirmed: AnonymizationState = AnonymizationState.ENABLED,
        change: AnonymizationChange = AnonymizationChange(),
        loaded: Boolean = true,
    ) = anonymizationControlsState(connection, confirmed, false, loaded, change)

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

    @Test fun connectingAndUnloadedDisableSelection() {
        val connecting = state(connection = WebRtcConnectionState.CONNECTING)
        assertFalse(connecting.canChange)
        val unloaded = state(connection = WebRtcConnectionState.IDLE, loaded = false)
        assertFalse(unloaded.canChange)
        assertNull(unloaded.selectedEnabled)
    }

}
