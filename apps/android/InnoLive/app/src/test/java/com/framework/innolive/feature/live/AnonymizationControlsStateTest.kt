package com.framework.innolive.feature.live

import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
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
        assertEquals(UiText.Resource(R.string.anonymization_needs_check), unknown.label)
    }

    @Test fun changingKeepsConfirmedValueAndPreventsDuplicateRequests() {
        val pending = state(change = AnonymizationChange(status = AnonymizationChangeStatus.CHANGING, requestedEnabled = false))
        assertEquals(true, pending.selectedEnabled)
        assertFalse(pending.canChange)
        assertEquals(UiText.Resource(R.string.anonymization_changing), pending.label)
        val failed = state(change = AnonymizationChange(
            status = AnonymizationChangeStatus.FAILED,
            errorMessage = UiText.Resource(R.string.error_anonymization_request),
        ))
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

    @Test fun processingLocationSwitchDisablesAnonymizationToggle() {
        val state = anonymizationControlsState(
            WebRtcConnectionState.CONNECTED,
            AnonymizationState.ENABLED,
            selected = true,
            loaded = true,
            change = AnonymizationChange(),
            processingModeChanging = true,
        )
        assertEquals(true, state.selectedEnabled)
        assertFalse(state.canChange)
    }

}
