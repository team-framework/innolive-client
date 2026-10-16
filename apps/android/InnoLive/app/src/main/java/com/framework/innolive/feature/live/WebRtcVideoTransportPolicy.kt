package com.framework.innolive.feature.live
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.json.JSONObject
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver

/** 서버는 세션 해상도로 디코더를 고정하므로 송신 범위도 그 해상도에 맞춥니다. */
internal data class VideoSendBitrate(val minBps: Int, val maxBps: Int)

internal fun videoSendBitrateFor(resolution: DirectPublishResolution) = when (resolution) {
    DirectPublishResolution.HD_720 -> VideoSendBitrate(minBps = 1_500_000, maxBps = 3_000_000)
    DirectPublishResolution.FHD -> VideoSendBitrate(minBps = 3_000_000, maxBps = 6_000_000)
}

/**
 * 기본값은 초기 대역폭 추정과 CPU 부하에 해상도를 먼저 깎아 180×320부터 시작합니다.
 * 해상도를 유지하고 하한 비트레이트로 초기 구간을 끌어올립니다.
 */
internal fun applyVideoSendPolicy(parameters: RtpParameters, resolution: DirectPublishResolution) {
    parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
    applyVideoSendEncodings(parameters.encodings, resolution)
}

internal fun applyVideoSendEncodings(encodings: List<RtpParameters.Encoding>, resolution: DirectPublishResolution) {
    val bitrate = videoSendBitrateFor(resolution)
    for (encoding in encodings) {
        encoding.minBitrateBps = bitrate.minBps
        encoding.maxBitrateBps = bitrate.maxBps
        encoding.maxFramerate = 30
        encoding.scaleResolutionDownBy = 1.0
    }
}

/** Absent or malformed capability keeps compatibility with legacy return-only servers. */
internal fun parseSendOnlyVideoSupport(payload: JSONObject): Boolean =
    payload.optJSONObject("features")?.opt("send_only_video") == true

internal fun videoDirectionForAI(onDevice: Boolean, sendOnlySupported: Boolean) =
    if (onDevice && sendOnlySupported) RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
    else RtpTransceiver.RtpTransceiverDirection.SEND_RECV

/** Release authentication uses HTTPS; the debug manifest already permits IPv4 loopback HTTP. */
internal fun isAllowedWebRtcServer(scheme: String, host: String, debug: Boolean): Boolean =
    scheme == "https" || (debug && scheme == "http" && host == "127.0.0.1")
