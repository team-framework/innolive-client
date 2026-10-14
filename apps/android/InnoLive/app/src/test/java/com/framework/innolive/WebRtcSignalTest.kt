package com.framework.innolive

import com.framework.innolive.feature.live.ServerMessage
import com.framework.innolive.feature.live.SignalingSessionMismatchException
import com.framework.innolive.feature.live.parseServerMessage
import java.io.File
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcSignalTest {
    @Test
    fun reportsFieldNameForInvalidStringAndBooleanTypes() {
        val stringError = assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage("""{"type":123}""")
        }
        assertEquals("signaling 응답의 type가 문자열이 아닙니다.", stringError.message)

        val booleanError = assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"ice_candidate_added","session_id":"session","end_of_candidates":"false"}""",
            )
        }
        assertEquals("signaling 응답의 end_of_candidates가 Boolean이 아닙니다.", booleanError.message)
    }

    @Test
    fun parsesServerAnswerAcknowledgementAndError() {
        assertEquals(
            ServerMessage.Answer(
                sessionId = "session",
                sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n",
            ),
            parseServerMessage(
                """{"type":"answer","session_id":"session","sdp":"v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"}""",
                expectedSessionId = "session",
            ),
        )
        assertEquals(
            ServerMessage.IceCandidateAdded(
                sessionId = "session",
                endOfCandidates = false,
                queued = true,
                iceConnectionState = "checking",
                connectionState = "connecting",
            ),
            parseServerMessage(
                """{"type":"ice_candidate_added","session_id":"session","end_of_candidates":false,"queued":true,"ice_connection_state":"checking","connection_state":"connecting"}""",
                expectedSessionId = "session",
            ),
        )
        assertEquals(
            ServerMessage.Error(
                code = "forbidden",
                message = "Session owner token is invalid.",
            ),
            parseServerMessage(
                """{"type":"error","error":{"code":"forbidden","message":"Session owner token is invalid."}}""",
            ),
        )
        assertEquals(
            ServerMessage.Error(
                code = "bad_request",
                message = "Unsupported signaling message type.",
            ),
            parseServerMessage(
                """{"type":"error","error":{"code":"bad_request","message":"Unsupported signaling message type.","details":{"type":"unknown"}}}""",
            ),
        )
    }

    @Test
    fun rejectsMissingRequiredSessionAndIceFields() {
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage("""{"type":"answer","sdp":"v=0"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage("""{"type":"ice_candidate_added","session_id":"session"}""")
        }
    }

    @Test
    fun rejectsMissingOrBlankErrorFields() {
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage("""{"type":"error"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage("""{"type":"error","error":{}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"error","error":{"message":"message"}}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"error","error":{"code":"code"}}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"error","error":{"code":"   ","message":"message"}}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"error","error":{"code":"code","message":"\t\n"}}""",
            )
        }
    }

    @Test
    fun rejectsAnswerAndIceAcknowledgementFromAnotherSession() {
        val answerMismatch = assertThrows(SignalingSessionMismatchException::class.java) {
            parseServerMessage(
                """{"type":"answer","session_id":"old-session","sdp":"v=0"}""",
                expectedSessionId = "current-session",
            )
        }
        assertEquals("current-session", answerMismatch.expectedSessionId)
        assertEquals("old-session", answerMismatch.actualSessionId)

        assertThrows(SignalingSessionMismatchException::class.java) {
            parseServerMessage(
                """{"type":"ice_candidate_added","session_id":"old-session","end_of_candidates":true,"queued":false,"ice_connection_state":"completed","connection_state":"connected"}""",
                expectedSessionId = "current-session",
            )
        }
    }

    @Test
    fun rejectsUnexpectedRootAndErrorProperties() {
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"answer","session_id":"session","sdp":"v=0","unexpected":true}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"error","error":{"code":"bad_request","message":"message","unexpected":true}}""",
            )
        }
    }

    @Test
    fun rejectsStringBooleansAndNonObjectErrorDetails() {
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"ice_candidate_added","session_id":"session","end_of_candidates":"false","queued":true,"ice_connection_state":"checking","connection_state":"connecting"}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage(
                """{"type":"error","error":{"code":"bad_request","message":"message","details":"not-an-object"}}""",
            )
        }
    }

    @Test
    fun recoveryMessagesKeepNegotiationGenerationAndRemoteCandidate() {
        val negotiationId = "a0cf0271-4332-4c75-bdc2-3a6af76e874b"
        assertEquals(
            ServerMessage.Answer("session", "v=0", negotiationId),
            parseServerMessage(
                """{"type":"answer","session_id":"session","negotiation_id":"$negotiationId","sdp":"v=0"}""",
                expectedSessionId = "session",
            ),
        )
        val candidate = parseServerMessage(
            """{"type":"ice_candidate","session_id":"session","negotiation_id":"$negotiationId","candidate":"candidate:1 1 UDP 1 127.0.0.1 12345 typ host","sdpMid":"0","sdpMLineIndex":0}""",
            expectedSessionId = "session",
        ) as ServerMessage.RemoteIceCandidate
        assertEquals(negotiationId, candidate.negotiationId)
        assertEquals("0", candidate.candidate?.sdpMid)
        assertEquals(0, candidate.candidate?.sdpMLineIndex)
    }

    @Test
    fun rejectsAnswersWithoutUsableSdpUnknownTypesAndNonJsonPayloads() {
        for (payload in listOf(
            """{"type":"answer","session_id":"session"}""",
            """{"type":"answer","session_id":"session","sdp":""}""",
            """{"type":"answer","session_id":"session","sdp":"  "}""",
            """{"type":"answer","session_id":"session","sdp":null}""",
            """{"type":"offer","session_id":"session","sdp":"v=0"}""",
            """{"type":"status","session_id":"session"}""",
        )) {
            assertThrows(payload, IllegalArgumentException::class.java) { parseServerMessage(payload, "session") }
        }
        assertThrows(JSONException::class.java) { parseServerMessage("not json", "session") }
        assertThrows(JSONException::class.java) { parseServerMessage("", "session") }
    }

    @Test
    fun remoteCandidateRequiresMatchingSessionAndWellFormedFields() {
        val base = "\"type\":\"ice_candidate\",\"session_id\":\"session\",\"candidate\":\"candidate:1 1 UDP 1 127.0.0.1 1 typ host\""
        assertThrows(IllegalArgumentException::class.java) {
            parseServerMessage("""{"type":"ice_candidate","candidate":"candidate:1"}""", "session")
        }
        assertThrows(SignalingSessionMismatchException::class.java) {
            parseServerMessage("{${base.replace("\"session\"", "\"old\"")}}", "session")
        }
        for (field in listOf(
            "\"negotiation_id\":1",
            "\"negotiation_id\":\" \"",
            "\"sdpMLineIndex\":-1",
            "\"sdpMLineIndex\":\"0\"",
            "\"sdpMid\":5",
        )) {
            assertThrows(field, IllegalArgumentException::class.java) { parseServerMessage("{$base,$field}", "session") }
        }
    }

    @Test
    fun nullOrAbsentCandidateMeansEndOfCandidates() {
        for (payload in listOf(
            """{"type":"ice_candidate","session_id":"session","candidate":null}""",
            """{"type":"ice_candidate","session_id":"session","negotiation_id":"n1"}""",
        )) {
            val message = parseServerMessage(payload, "session") as ServerMessage.RemoteIceCandidate
            assertNull(payload, message.candidate)
        }
    }

    /** v2.schema.json: candidate는 minLength 없는 string|null이다. 빈 문자열은 후보 종료이지 프로토콜 오류가 아니다. */
    @Test
    fun emptyStringCandidateIsEndOfCandidatesNotAProtocolError() {
        val message = parseServerMessage("""{"type":"ice_candidate","session_id":"session","candidate":""}""", "session")
        assertNull((message as ServerMessage.RemoteIceCandidate).candidate)
    }

    @Test
    fun decodesSharedWithdrawalErrorFixture() {
        val root = generateSequence(File(checkNotNull(System.getProperty("innolive.android.resDir")))) { it.parentFile }
            .first { File(it, "contracts/fixtures").isDirectory }
        val payload = File(root, "contracts/fixtures/signaling-withdrawal-error.v2.json").readText()

        assertEquals(
            ServerMessage.Error("withdrawal_in_progress", "Account deletion is already in progress. Retry shortly."),
            parseServerMessage(payload, "session"),
        )
    }
}
