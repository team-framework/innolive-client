package com.framework.innolive.feature.live

import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import org.junit.Assert.*
import org.junit.Test

class AnonymizationChangeTest {
    private fun connected(): WebRtcSessionState {
        val s = WebRtcSessionState().beginConnection()
        return s.connectionChanged(s.generation, WebRtcConnectionState.CONNECTED)
            .anonymizationConfirmed(s.generation, AnonymizationState.ENABLED)
    }

    @Test fun payloadAndResponseRequireActualBooleanAndMatchingSession() {
        assertEquals(false, anonymizationPayload(false).get("enabled"))
        assertEquals(true, anonymizationPayload(true).get("enabled"))
        for ((value, expected) in listOf("true" to AnonymizationState.ENABLED, "false" to AnonymizationState.DISABLED)) {
            assertEquals(expected, parseAnonymizationResponse("""{"session_id":"s","media":{"anonymization_enabled":$value}}""", "s"))
        }
        for (body in listOf("{}", """{"session_id":"other","media":{"anonymization_enabled":false}}""",
            """{"session_id":"s","media":{"anonymization_enabled":"false"}}""",
            """{"session_id":"s","media":{"anonymization_enabled":null}}""")) {
            assertThrows(IllegalArgumentException::class.java) { parseAnonymizationResponse(body, "s") }
        }
    }

    @Test fun inProgressKeepsConfirmedValueAndRejectsBothDuplicateAndOppositeRequest() {
        val pending = connected().beginAnonymizationChange(false)
        assertEquals(AnonymizationState.ENABLED, pending.anonymization)
        assertEquals(AnonymizationChangeStatus.CHANGING, pending.anonymizationChange.status)
        assertEquals(pending, pending.beginAnonymizationChange(false))
        assertEquals(pending, pending.beginAnonymizationChange(true))
        val done = pending.finishAnonymizationChange(pending.generation, pending.anonymizationChange.requestId, AnonymizationState.DISABLED, null)
        assertEquals(WebRtcConnectionState.CONNECTED, done.connection)
        assertEquals(AnonymizationState.DISABLED, done.anonymization)
        assertEquals(AnonymizationChangeStatus.IDLE, done.anonymizationChange.status)
    }

    @Test fun failedRequestPreservesLastKnownValueAndCanRetryEvenSameValue() {
        val pending = connected().beginAnonymizationChange(false)
        val error = UiText.Resource(R.string.error_anonymization_confirmation)
        val failed = pending.finishAnonymizationChange(pending.generation, pending.anonymizationChange.requestId, null, error)
        assertEquals(AnonymizationState.ENABLED, failed.anonymization)
        assertEquals(WebRtcConnectionState.CONNECTED, failed.connection)
        assertEquals(error, failed.anonymizationChange.errorMessage)
        val retry = failed.beginAnonymizationChange(true)
        assertEquals(AnonymizationChangeStatus.CHANGING, retry.anonymizationChange.status)
        assertNull(retry.anonymizationChange.errorMessage)
        assertEquals(retry, retry.finishAnonymizationChange(pending.generation, pending.anonymizationChange.requestId, AnonymizationState.DISABLED, null))
    }

    @Test fun lateResponsesAfterCloseFailureAndReconnectAreIgnored() {
        val p = connected().beginAnonymizationChange(false)
        for (ended in listOf(p.endConnection(), p.connectionChanged(p.generation, WebRtcConnectionState.FAILED), p.endConnection().beginConnection())) {
            assertEquals(AnonymizationChangeStatus.IDLE, ended.anonymizationChange.status)
            assertEquals(AnonymizationState.UNKNOWN, ended.anonymization)
            assertEquals(ended, ended.finishAnonymizationChange(p.generation, p.anonymizationChange.requestId, AnonymizationState.DISABLED, null))
            assertEquals(ended, ended.beginAnonymizationChange(false))
        }
    }

    @Test fun serverMismatchUsesConfirmedServerValueAndReportsFailure() {
        val p = connected().beginAnonymizationChange(false)
        val result = p.finishAnonymizationChange(
            p.generation,
            p.anonymizationChange.requestId,
            AnonymizationState.ENABLED,
            UiText.Resource(R.string.error_anonymization_not_applied),
        )
        assertEquals(AnonymizationState.ENABLED, result.anonymization)
        assertEquals(AnonymizationChangeStatus.FAILED, result.anonymizationChange.status)
    }
}
