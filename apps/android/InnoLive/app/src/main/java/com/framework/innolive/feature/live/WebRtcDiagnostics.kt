package com.framework.innolive.feature.live

import org.webrtc.PeerConnection
import org.webrtc.RTCStats

internal enum class PeerConnectionTransition {
    CONNECTED,
    RECOVERING,
    FAILED,
    IGNORE,
}

internal fun peerConnectionTransition(
    state: PeerConnection.PeerConnectionState,
): PeerConnectionTransition = when (state) {
    PeerConnection.PeerConnectionState.CONNECTED -> PeerConnectionTransition.CONNECTED
    PeerConnection.PeerConnectionState.DISCONNECTED -> PeerConnectionTransition.RECOVERING
    PeerConnection.PeerConnectionState.FAILED -> PeerConnectionTransition.FAILED
    else -> PeerConnectionTransition.IGNORE
}

internal data class WebRtcStatsSummary(
    val outboundPackets: Long? = null,
    val inboundPackets: Long? = null,
    val lostPackets: Long? = null,
    val jitterSeconds: Double? = null,
    val roundTripTimeSeconds: Double? = null,
    val framesEncoded: Long? = null,
    val framesDecoded: Long? = null,
    val frameWidth: Long? = null,
    val frameHeight: Long? = null,
) {
    fun toLogFields(): String = buildList {
        outboundPackets?.let { add("out_packets=$it") }
        inboundPackets?.let { add("in_packets=$it") }
        lostPackets?.let { add("lost_packets=$it") }
        jitterSeconds?.let { add("jitter_seconds=$it") }
        roundTripTimeSeconds?.let { add("rtt_seconds=$it") }
        framesEncoded?.let { add("frames_encoded=$it") }
        framesDecoded?.let { add("frames_decoded=$it") }
        frameWidth?.let { add("frame_width=$it") }
        frameHeight?.let { add("frame_height=$it") }
    }.joinToString(" ").ifEmpty { "available=false" }
}

internal fun summarizeWebRtcStats(stats: Collection<RTCStats>): WebRtcStatsSummary {
    var outboundPackets: Long? = null
    var inboundPackets: Long? = null
    var lostPackets: Long? = null
    var jitterSeconds: Double? = null
    var roundTripTimeSeconds: Double? = null
    var framesEncoded: Long? = null
    var framesDecoded: Long? = null
    var frameWidth: Long? = null
    var frameHeight: Long? = null

    stats.forEach { stat ->
        val members = stat.members
        val mediaKind = members["kind"] ?: members["mediaType"]
        when {
            stat.type == "outbound-rtp" && mediaKind == "video" -> {
                outboundPackets = members.long("packetsSent") ?: outboundPackets
                framesEncoded = members.long("framesEncoded") ?: framesEncoded
                frameWidth = members.long("frameWidth") ?: frameWidth
                frameHeight = members.long("frameHeight") ?: frameHeight
            }

            stat.type == "inbound-rtp" && mediaKind == "video" -> {
                inboundPackets = members.long("packetsReceived") ?: inboundPackets
                lostPackets = members.long("packetsLost") ?: lostPackets
                jitterSeconds = members.double("jitter") ?: jitterSeconds
                framesDecoded = members.long("framesDecoded") ?: framesDecoded
                frameWidth = members.long("frameWidth") ?: frameWidth
                frameHeight = members.long("frameHeight") ?: frameHeight
            }

            stat.type == "remote-inbound-rtp" && mediaKind == "video" -> {
                lostPackets = members.long("packetsLost") ?: lostPackets
                jitterSeconds = members.double("jitter") ?: jitterSeconds
                roundTripTimeSeconds = members.double("roundTripTime") ?: roundTripTimeSeconds
            }

            stat.type == "candidate-pair" && members["nominated"] == true -> {
                roundTripTimeSeconds =
                    members.double("currentRoundTripTime") ?: roundTripTimeSeconds
            }
        }
    }

    return WebRtcStatsSummary(
        outboundPackets = outboundPackets,
        inboundPackets = inboundPackets,
        lostPackets = lostPackets,
        jitterSeconds = jitterSeconds,
        roundTripTimeSeconds = roundTripTimeSeconds,
        framesEncoded = framesEncoded,
        framesDecoded = framesDecoded,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
    )
}

private fun Map<String, Any>.long(key: String): Long? = (this[key] as? Number)?.toLong()

private fun Map<String, Any>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
