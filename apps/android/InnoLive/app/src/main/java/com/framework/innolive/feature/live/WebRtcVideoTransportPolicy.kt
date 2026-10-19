package com.framework.innolive.feature.live
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.json.JSONObject
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver

/**
 * 서버는 세션 해상도로 디코더를 고정하므로 송신 범위도 그 해상도에 맞춥니다.
 * 하한은 혼잡 구간에서 대역폭 추정이 내려갈 여지를 남깁니다. 하한이 링크보다 높으면
 * 패킷 손실과 서버의 키프레임 반복 요청으로 이어져 화면이 블록으로 깨집니다.
 * 초기 구간은 setBitrate의 시작 비트레이트가 끌어올립니다.
 */
internal data class VideoSendBitrate(val minBps: Int, val maxBps: Int)

internal fun videoSendBitrateFor(resolution: DirectPublishResolution) = when (resolution) {
    DirectPublishResolution.HD_720 -> VideoSendBitrate(minBps = 500_000, maxBps = 3_000_000)
    DirectPublishResolution.FHD -> VideoSendBitrate(minBps = 1_000_000, maxBps = 6_000_000)
}

/** 서버 디코더 장변과 같습니다. 720p 세션은 1280, FHD 세션은 1920입니다. */
internal fun videoSendLongEdgeFor(resolution: DirectPublishResolution) = when (resolution) {
    DirectPublishResolution.HD_720 -> 1280
    DirectPublishResolution.FHD -> 1920
}

/**
 * 서버가 디코딩 직후 세션 장변으로 줄이므로 그보다 큰 캡처는 업링크와 서버 디코딩만 늘립니다.
 * 캡처 비율을 유지한 채 장변을 세션 장변 이하로 줄이고, 작은 캡처는 확대하지 않습니다.
 */
internal fun videoSendFormatFor(width: Int, height: Int, resolution: DirectPublishResolution): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    val cap = videoSendLongEdgeFor(resolution)
    if (longEdge <= cap) return width to height
    // yuv420 인코더가 짝수 치수를 요구하므로 짧은 변을 짝수로 내립니다.
    val shortEdge = (minOf(width, height).toLong() * cap / longEdge).toInt() / 2 * 2
    return if (width >= height) cap to shortEdge else shortEdge to cap
}

/**
 * 기본값은 초기 대역폭 추정과 CPU 부하에 해상도를 먼저 깎아 180×320부터 시작합니다.
 * 해상도를 유지하고 혼잡 구간에서는 비트레이트와 프레임률을 낮춥니다.
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
