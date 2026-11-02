package com.framework.innolive.feature.live

import org.webrtc.RtpCapabilities
import org.webrtc.VideoCodecInfo

/** 정확히 이 Baseline 프로필만 Pion 서버의 연결 피어 테스트로 검증돼 있습니다. */
internal fun isServerCompatibleH264Baseline(name:String,params:Map<String,String>):Boolean =
    name.equals("H264",ignoreCase=true) &&
        params["profile-level-id"]?.equals("42e01f",ignoreCase=true)==true &&
        params["packetization-mode"]=="1"

/** 기존 서버 AI 경로는 검증된 하드웨어 H264 Baseline 프로필을 우선합니다. */
internal fun preferredHardwareVideoCodecs(capabilities:List<RtpCapabilities.CodecCapability>,
    hardwareFormats:List<VideoCodecInfo>):List<RtpCapabilities.CodecCapability>? {
    if(hardwareFormats.none {isServerCompatibleH264Baseline(it.name,it.params)})return null
    val baseline=capabilities.filter {isServerCompatibleH264Baseline(it.name,it.parameters)}
    if(baseline.isEmpty())return null
    return baseline+capabilities.filterNot {it in baseline}
}

/** 인코더 구현을 고르기 전에 iOS의 온디바이스 경로와 맞춥니다. */
internal fun preferredServerVideoCodecs(
    capabilities: List<RtpCapabilities.CodecCapability>,
): List<RtpCapabilities.CodecCapability>? {
    val vp8 = capabilities.filter { it.name.equals("VP8", ignoreCase = true) }
    if (vp8.isEmpty()) return null
    return vp8 + capabilities.filterNot { it.name.equals("VP8", ignoreCase = true) }
}

/** AI 처리 위치는 PeerConnection offer를 만들 때 정해집니다. */
internal fun preferredVideoCodecsForAI(
    onDevice: Boolean,
    capabilities: List<RtpCapabilities.CodecCapability>,
    hardwareFormats: List<VideoCodecInfo>,
    sendOnlyVideoSupported: Boolean = false,
): List<RtpCapabilities.CodecCapability>? {
    // 디버그 계측 테스트에서는 같은 빌드로 두 협상 경로를 비교할 수 있습니다.
    if (com.framework.innolive.BuildConfig.DEBUG && onDevice) {
        when (System.getProperty("innolive.codec.probe")) {
            "H264" -> return preferredHardwareVideoCodecs(capabilities, hardwareFormats)
            "VP8" -> return preferredServerVideoCodecs(capabilities)
        }
    }
    val hardware = preferredHardwareVideoCodecs(capabilities, hardwareFormats)
    return if (onDevice) {
        if (sendOnlyVideoSupported) hardware ?: preferredServerVideoCodecs(capabilities)
        else preferredServerVideoCodecs(capabilities)
    } else hardware
}
