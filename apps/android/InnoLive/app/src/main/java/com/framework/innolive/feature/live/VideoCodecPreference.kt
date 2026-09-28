package com.framework.innolive.feature.live

import org.webrtc.RtpCapabilities
import org.webrtc.VideoCodecInfo

/** This exact Baseline profile is covered by the Pion server's connected-peer test. */
internal fun isServerCompatibleH264Baseline(name:String,params:Map<String,String>):Boolean =
    name.equals("H264",ignoreCase=true) &&
        params["profile-level-id"]?.equals("42e01f",ignoreCase=true)==true &&
        params["packetization-mode"]=="1"

/** Hardware capability benchmark preference; production server negotiation uses VP8. */
internal fun preferredHardwareVideoCodecs(capabilities:List<RtpCapabilities.CodecCapability>,
    hardwareFormats:List<VideoCodecInfo>):List<RtpCapabilities.CodecCapability>? {
    if(hardwareFormats.none {isServerCompatibleH264Baseline(it.name,it.params)})return null
    val baseline=capabilities.filter {isServerCompatibleH264Baseline(it.name,it.parameters)}
    if(baseline.isEmpty())return null
    return baseline+capabilities.filterNot {it in baseline}
}

/** Match iOS's production server path before choosing an encoder implementation. */
internal fun preferredServerVideoCodecs(
    capabilities: List<RtpCapabilities.CodecCapability>,
): List<RtpCapabilities.CodecCapability>? {
    val vp8 = capabilities.filter { it.name.equals("VP8", ignoreCase = true) }
    if (vp8.isEmpty()) return null
    return vp8 + capabilities.filterNot { it.name.equals("VP8", ignoreCase = true) }
}
