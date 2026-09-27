package com.framework.innolive.feature.live

import org.webrtc.RtpCapabilities
import org.webrtc.VideoCodecInfo

/** This exact Baseline profile is covered by the Pion server's connected-peer test. */
internal fun isServerCompatibleH264Baseline(name:String,params:Map<String,String>):Boolean =
    name.equals("H264",ignoreCase=true) &&
        params["profile-level-id"]?.equals("42e01f",ignoreCase=true)==true &&
        params["packetization-mode"]=="1"

internal fun preferredHardwareVideoCodecs(capabilities:List<RtpCapabilities.CodecCapability>,
    hardwareFormats:List<VideoCodecInfo>):List<RtpCapabilities.CodecCapability>? {
    if(hardwareFormats.none {isServerCompatibleH264Baseline(it.name,it.params)})return null
    val baseline=capabilities.filter {isServerCompatibleH264Baseline(it.name,it.parameters)}
    if(baseline.isEmpty())return null
    return baseline+capabilities.filterNot {it in baseline}
}
