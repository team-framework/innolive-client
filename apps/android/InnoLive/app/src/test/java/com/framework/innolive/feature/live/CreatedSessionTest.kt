package com.framework.innolive.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CreatedSessionTest {
    @Test fun readsConfirmedOnAndOffWithoutInferringFromConnection() {
        for ((json, expected) in listOf("true" to AnonymizationState.ENABLED, "false" to AnonymizationState.DISABLED)) {
            val session = parseCreatedSession("""{"session_id":"session","owner_token":"owner","media":{"anonymization_enabled":$json}}""")
            assertEquals("session", session.sessionId)
            assertEquals("owner", session.ownerToken)
            assertEquals(expected, session.anonymizationState)
        }
    }

    @Test fun missingNullAndInvalidFieldsRemainUnknown() {
        for (fields in listOf("", ",\"media\":null", ",\"media\":{}", ",\"media\":false",
            ",\"media\":{\"anonymization_enabled\":null}",
            ",\"media\":{\"anonymization_enabled\":\"false\"}",
            ",\"media\":{\"anonymization_enabled\":0}")) {
            assertEquals(AnonymizationState.UNKNOWN,
                parseCreatedSession("""{"session_id":"session","owner_token":"owner"$fields}""").anonymizationState)
        }
    }

    @Test fun missingCredentialsStillRejectSession() {
        for (payload in listOf("{}", """{"session_id":"session"}""", """{"owner_token":"owner"}""")) {
            assertThrows(IllegalArgumentException::class.java) { parseCreatedSession(payload) }
        }
    }
}
