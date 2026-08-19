package com.framework.innolive

import com.framework.innolive.feature.live.ServerMessage
import com.framework.innolive.feature.live.parseServerMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class WebRtcSignalTest {
    @Test
    fun parsesServerAnswerAcknowledgementAndError() {
        assertEquals(
            ServerMessage.Answer("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"),
            parseServerMessage(
                """{"type":"answer","session_id":"session","sdp":"v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"}""",
            ),
        )
        assertEquals(
            ServerMessage.IceCandidateAdded,
            parseServerMessage("""{"type":"ice_candidate_added","session_id":"session"}"""),
        )
        assertEquals(
            ServerMessage.Error("Session owner token is invalid."),
            parseServerMessage(
                """{"type":"error","error":{"code":"forbidden","message":"Session owner token is invalid."}}""",
            ),
        )
    }
}
