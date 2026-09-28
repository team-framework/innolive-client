package com.framework.innolive.feature.live

/** Numeric WebRTC video diagnostics; no media, session IDs or credentials. */
internal data class PrivacyVideoStats(
    val sampledAtNs: Long,
    val encoded: Long?,
    val sent: Long?,
    val packets: Long?,
    val bytes: Long?,
    val encodeSeconds: Double?,
) {
    fun interval(previous: PrivacyVideoStats?): String {
        val seconds = previous?.let { (sampledAtNs - it.sampledAtNs) / 1e9 }
            ?.takeIf { it > 0.0 }
        fun delta(current: Long?, before: Long?): Long? =
            if (current != null && before != null) (current - before).takeIf { it >= 0 } else null
        val encodedFrames = delta(encoded, previous?.encoded)
        val sentFrames = delta(sent, previous?.sent)
        val transferred = delta(bytes, previous?.bytes)
        val encodeTime = if (encodeSeconds != null && previous?.encodeSeconds != null)
            (encodeSeconds - previous.encodeSeconds).takeIf { it >= 0.0 } else null
        val encodedFps = if (seconds != null && encodedFrames != null) encodedFrames / seconds else null
        val sentFps = if (seconds != null && sentFrames != null) sentFrames / seconds else null
        val meanEncodeMs = if (encodeTime != null && encodedFrames != null && encodedFrames > 0)
            encodeTime * 1000 / encodedFrames else null
        val bitrateKbps = if (seconds != null && transferred != null)
            transferred * 8 / seconds / 1000 else null
        return "frames_encoded=$encoded frames_sent=$sent packets_sent=$packets " +
            "encoded_fps=$encodedFps sent_fps=$sentFps mean_encode_ms=$meanEncodeMs " +
            "bitrate_kbps=$bitrateKbps"
    }

    companion object {
        fun from(members: Map<String, *>, sampledAtNs: Long) = PrivacyVideoStats(
            sampledAtNs,
            (members["framesEncoded"] as? Number)?.toLong(),
            (members["framesSent"] as? Number)?.toLong(),
            (members["packetsSent"] as? Number)?.toLong(),
            (members["bytesSent"] as? Number)?.toLong(),
            (members["totalEncodeTime"] as? Number)?.toDouble(),
        )
    }
}
