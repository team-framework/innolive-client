package com.framework.innolive.feature.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.RtpCapabilities
import org.webrtc.VideoCodecInfo

class VideoCodecPreferenceTest {
    @Test fun onlyPionVerifiedBaselineProfileCanPrecedeVp8() {
        val baseline=mapOf("profile-level-id" to "42e01f","packetization-mode" to "1")
        assertTrue(isServerCompatibleH264Baseline("H264",baseline))
        assertFalse(isServerCompatibleH264Baseline("H264",baseline+ ("profile-level-id" to "640c1f")))
        assertFalse(isServerCompatibleH264Baseline("H264",baseline+ ("packetization-mode" to "0")))
        assertFalse(isServerCompatibleH264Baseline("VP8",baseline))
        fun codec(name:String,params:Map<String,String>)=RtpCapabilities.CodecCapability().apply {
            this.name=name;this.parameters=params
        }
        val vp8=codec("VP8",emptyMap())
        val h264=codec("H264",baseline)
        val high=codec("H264",baseline+("profile-level-id" to "640c1f"))
        val hardware=listOf(VideoCodecInfo("H264",baseline,emptyList()))
        assertTrue(preferredHardwareVideoCodecs(listOf(vp8,high,h264),hardware)==listOf(h264,vp8,high))
        assertTrue(preferredHardwareVideoCodecs(listOf(vp8,high),hardware)==null)
        assertTrue(preferredHardwareVideoCodecs(listOf(vp8,h264),emptyList())==null)
    }
}
