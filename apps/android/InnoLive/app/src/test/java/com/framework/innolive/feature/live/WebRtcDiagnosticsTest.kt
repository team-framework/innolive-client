package com.framework.innolive.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.webrtc.PeerConnection
import org.webrtc.RTCStats

class WebRtcDiagnosticsTest {
    @Test
    fun disconnectedConnectionEntersRecoveryInsteadOfFailing() {
        assertEquals(
            PeerConnectionTransition.RECOVERING,
            peerConnectionTransition(PeerConnection.PeerConnectionState.DISCONNECTED),
        )
        assertEquals(
            PeerConnectionTransition.FAILED,
            peerConnectionTransition(PeerConnection.PeerConnectionState.FAILED),
        )
        assertEquals(
            PeerConnectionTransition.CONNECTED,
            peerConnectionTransition(PeerConnection.PeerConnectionState.CONNECTED),
        )
        assertEquals(
            PeerConnectionTransition.IGNORE,
            peerConnectionTransition(PeerConnection.PeerConnectionState.CLOSED),
        )
    }

    @Test
    fun summarizesOnlyNonSensitiveMediaAndNetworkMeasurements() {
        val summary = summarizeWebRtcStats(
            listOf(
                stats(
                    type = "outbound-rtp",
                    members = mapOf(
                        "kind" to "video",
                        "packetsSent" to 120L,
                        "framesEncoded" to 30L,
                        "frameWidth" to 720L,
                        "frameHeight" to 1280L,
                        "ssrc" to 123456L,
                    ),
                ),
                stats(
                    type = "remote-inbound-rtp",
                    members = mapOf(
                        "kind" to "video",
                        "packetsLost" to 4L,
                        "jitter" to 0.025,
                        "roundTripTime" to 0.18,
                    ),
                ),
            ),
        )

        assertEquals(120L, summary.outboundPackets)
        assertEquals(4L, summary.lostPackets)
        assertEquals(0.025, summary.jitterSeconds)
        assertEquals(0.18, summary.roundTripTimeSeconds)
        assertEquals(720L, summary.frameWidth)
        assertEquals(1280L, summary.frameHeight)
        assertFalse(summary.toLogFields().contains("ssrc"))
        assertFalse(summary.toLogFields().contains("123456"))
    }

    private fun stats(type: String, members: Map<String, Any>) = RTCStats(
        0L,
        type,
        "private-stat-id",
        members,
    )
}
