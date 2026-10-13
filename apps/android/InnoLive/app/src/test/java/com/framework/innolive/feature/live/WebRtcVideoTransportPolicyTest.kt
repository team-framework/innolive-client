package com.framework.innolive.feature.live
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
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
}
