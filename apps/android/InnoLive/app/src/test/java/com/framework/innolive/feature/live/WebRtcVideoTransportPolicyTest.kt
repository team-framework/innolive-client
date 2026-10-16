package com.framework.innolive.feature.live
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver.RtpTransceiverDirection.*
class WebRtcVideoTransportPolicyTest {
    @Test fun productionStillRequiresHttpsAndDebugOnlyAllowsLiteralLoopbackHttp() {
        assertTrue(isAllowedWebRtcServer("https","example.invalid",false))
        assertFalse(isAllowedWebRtcServer("http","127.0.0.1",false))
        assertTrue(isAllowedWebRtcServer("http","127.0.0.1",true))
        for (host in listOf("example.invalid","192.168.0.1","localhost","127.0.0.1.example.invalid"))
            assertFalse(isAllowedWebRtcServer("http",host,true))
    }
    @Test fun legacyAndMalformedConfigKeepReturnCompatibility() {
        for (raw in listOf("{}", "{\"features\":null}","{\"features\":{\"send_only_video\":false}}","{\"features\":{\"send_only_video\":\"true\"}}"))
            assertFalse(parseSendOnlyVideoSupport(JSONObject(raw)))
    }
    @Test fun onlyExplicitCapabilityEnablesOnDeviceSendOnly() {
        assertTrue(parseSendOnlyVideoSupport(JSONObject("{\"features\":{\"send_only_video\":true}}")))
        assertEquals(SEND_ONLY,videoDirectionForAI(true,true))
        assertEquals(SEND_RECV,videoDirectionForAI(true,false))
        assertEquals(SEND_RECV,videoDirectionForAI(false,true))
        assertEquals(SEND_RECV,videoDirectionForAI(false,false))
    }
    @Test fun sendBitrateRangeFollowsServerSessionResolution() {
        assertEquals(VideoSendBitrate(1_500_000,3_000_000),videoSendBitrateFor(DirectPublishResolution.HD_720))
        assertEquals(VideoSendBitrate(3_000_000,6_000_000),videoSendBitrateFor(DirectPublishResolution.FHD))
    }
    @Test fun sendEncodingsKeepFullResolutionWithinTheRange() {
        val encodings=listOf(RtpParameters.Encoding(null,true,2.0))
        applyVideoSendEncodings(encodings,DirectPublishResolution.FHD)
        val encoding=encodings.single()
        assertEquals(3_000_000,encoding.minBitrateBps)
        assertEquals(6_000_000,encoding.maxBitrateBps)
        assertEquals(30,encoding.maxFramerate)
        assertEquals(1.0,encoding.scaleResolutionDownBy!!,0.0)
        assertTrue(encoding.active)
    }
}
