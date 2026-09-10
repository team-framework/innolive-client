package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test

class WebRtcSessionStateTest {
    @Test fun connectedDoesNotImplyAnonymizationEnabled() {
        val starting = WebRtcSessionState().beginConnection()
        val connected = starting.connectionChanged(starting.generation, WebRtcConnectionState.CONNECTED)
        assertEquals(AnonymizationState.UNKNOWN, connected.anonymization)
        for (mode in listOf(AnonymizationState.ENABLED, AnonymizationState.DISABLED)) {
            val confirmed = connected.anonymizationConfirmed(connected.generation, mode)
            assertEquals(WebRtcConnectionState.CONNECTED, confirmed.connection)
            assertEquals(mode, confirmed.anonymization)
        }
    }

    @Test fun confirmationBeforeConnectionIsPreservedWhenConnected() {
        val starting = WebRtcSessionState().beginConnection()
        val confirmed = starting.anonymizationConfirmed(starting.generation, AnonymizationState.DISABLED)
        assertEquals(WebRtcConnectionState.CONNECTING, confirmed.connection)
        assertEquals(AnonymizationState.DISABLED,
            confirmed.connectionChanged(starting.generation, WebRtcConnectionState.CONNECTED).anonymization)
    }

    @Test fun failureAndIdleDiscardConfirmationAndRejectLateCallbacks() {
        for (terminal in listOf(WebRtcConnectionState.FAILED, WebRtcConnectionState.IDLE)) {
            val starting = WebRtcSessionState().beginConnection()
            val confirmed = starting.anonymizationConfirmed(starting.generation, AnonymizationState.ENABLED)
            val ended = confirmed.connectionChanged(starting.generation, terminal)
            assertEquals(AnonymizationState.UNKNOWN, ended.anonymization)
            assertEquals(ended, ended.anonymizationConfirmed(starting.generation, AnonymizationState.ENABLED))
            assertEquals(ended, ended.connectionChanged(starting.generation, WebRtcConnectionState.CONNECTED))
        }
    }

    @Test fun closeAndReconnectRejectEveryOldSessionCallback() {
        val first = WebRtcSessionState().beginConnection()
        val active = first.anonymizationConfirmed(first.generation, AnonymizationState.ENABLED)
            .connectionChanged(first.generation, WebRtcConnectionState.CONNECTED)
        val closed = active.endConnection()
        assertEquals(WebRtcConnectionState.IDLE, closed.connection)
        assertEquals(AnonymizationState.UNKNOWN, closed.anonymization)
        val next = closed.beginConnection()
        for (target in listOf(closed, next)) {
            assertEquals(target, target.anonymizationConfirmed(first.generation, AnonymizationState.ENABLED))
            assertEquals(target, target.connectionChanged(first.generation, WebRtcConnectionState.FAILED))
            assertEquals(target, target.connectionChanged(first.generation, WebRtcConnectionState.CONNECTED))
        }
        assertEquals(AnonymizationState.UNKNOWN, next.anonymization)
        assertEquals(AnonymizationState.DISABLED,
            next.anonymizationConfirmed(next.generation, AnonymizationState.DISABLED).anonymization)
    }

    @Test fun retryAfterFailureStartsWithoutPreviousConfirmation() {
        val first = WebRtcSessionState().beginConnection()
        val failed = first.anonymizationConfirmed(first.generation, AnonymizationState.ENABLED)
            .connectionChanged(first.generation, WebRtcConnectionState.FAILED)
        val retry = failed.beginConnection()
        assertEquals(WebRtcConnectionState.CONNECTING, retry.connection)
        assertEquals(AnonymizationState.UNKNOWN, retry.anonymization)
        assertNotEquals(first.generation, retry.generation)
        assertFalse(retry.acceptsCallback(first.generation))
    }

    @Test fun unknownSessionCannotAcceptConfirmation() {
        val idle = WebRtcSessionState()
        assertEquals(idle, idle.anonymizationConfirmed(idle.generation, AnonymizationState.DISABLED))
    }
}
