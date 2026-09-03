package com.framework.innolive

import com.framework.innolive.feature.live.ServerMessage
import com.framework.innolive.feature.live.SignalingSessionMismatchException
import com.framework.innolive.feature.live.parseServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcSignalTest {
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
}
