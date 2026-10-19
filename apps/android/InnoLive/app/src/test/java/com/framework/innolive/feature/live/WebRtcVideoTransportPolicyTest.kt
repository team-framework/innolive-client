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
        assertEquals(VideoSendBitrate(500_000,3_000_000),videoSendBitrateFor(DirectPublishResolution.HD_720))
        assertEquals(VideoSendBitrate(1_000_000,6_000_000),videoSendBitrateFor(DirectPublishResolution.FHD))
    }
    @Test fun sendFloorStaysBelowStartBitrate() {
        for (onDevice in listOf(false,true)) for (resolution in DirectPublishResolution.entries) {
            val range=videoSendBitrateFor(resolution)
            assertTrue(range.minBps<range.maxBps)
            val budget=uplinkBitratePolicyForAI(onDevice,resolution)
            if (onDevice) {
                // 온디바이스는 시작값을 주지 않아 하한만 남는다. 하한은 서버 AI와 같은 값이다.
                assertNull(budget)
                continue
            }
            assertTrue(range.minBps<=budget!!.startBps/2)
            assertEquals(range.maxBps,budget.maxBps)
        }
    }
    @Test fun senderParametersCarryFloorStartAndCapForEverySession() {
        for (onDevice in listOf(false,true)) for (resolution in DirectPublishResolution.entries) {
            val parameters=senderParameters(RtpParameters.Encoding(null,true,2.0))
            // WebRtcConnection 순서: addVideoTransceiver 뒤 offer 전 applyUplinkPolicy.
            applyVideoSendPolicy(parameters,resolution)
            assertEquals(!onDevice,applyUplinkSenderPolicy(parameters,onDevice,resolution))
            val range=videoSendBitrateFor(resolution)
            val encoding=parameters.encodings.single()
            assertEquals(RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION,parameters.degradationPreference)
            assertEquals(range.minBps,encoding.minBitrateBps)
            assertEquals(range.maxBps,encoding.maxBitrateBps)
            assertEquals(30,encoding.maxFramerate)
            assertEquals(1.0,encoding.scaleResolutionDownBy!!,0.0)
            assertTrue(encoding.active)
            // setBitrate(start,max)에 들어가는 값. 온디바이스는 setBitrate를 부르지 않는다.
            val budget=uplinkBitratePolicyForAI(onDevice,resolution)
            if (onDevice) assertNull(budget)
            else {
                assertTrue(range.minBps<budget!!.startBps&&budget.startBps<=encoding.maxBitrateBps!!)
                assertEquals(encoding.maxBitrateBps,budget.maxBps)
            }
        }
    }
    /** 생성자가 패키지 전용이라 네이티브 sender 없이 getParameters 결과와 같은 모양을 만든다. */
    private fun senderParameters(vararg encodings: RtpParameters.Encoding): RtpParameters {
        val constructor=RtpParameters::class.java.declaredConstructors.single { it.parameterCount==6 }
        constructor.isAccessible=true
        return constructor.newInstance("probe",null,null,emptyList<Any>(),encodings.toList(),emptyList<Any>()) as RtpParameters
    }
    @Test fun sendEncodingsKeepFullResolutionWithinTheRange() {
        val encodings=listOf(RtpParameters.Encoding(null,true,2.0))
        applyVideoSendEncodings(encodings,DirectPublishResolution.FHD)
        val encoding=encodings.single()
        assertEquals(1_000_000,encoding.minBitrateBps)
        assertEquals(6_000_000,encoding.maxBitrateBps)
        assertEquals(30,encoding.maxFramerate)
        assertEquals(1.0,encoding.scaleResolutionDownBy!!,0.0)
        assertTrue(encoding.active)
    }
    @Test fun sendFormatCapsLongEdgeAtServerSessionResolution() {
        assertEquals(720 to 1280,videoSendFormatFor(1080,1920,DirectPublishResolution.HD_720))
        assertEquals(1280 to 720,videoSendFormatFor(1920,1080,DirectPublishResolution.HD_720))
        assertEquals(1080 to 1920,videoSendFormatFor(1080,1920,DirectPublishResolution.FHD))
        assertEquals(1080 to 1920,videoSendFormatFor(2160,3840,DirectPublishResolution.FHD))
    }
    @Test fun sendFormatKeepsAspectRatioWithEvenDimensionsAndNeverUpscales() {
        assertEquals(960 to 1280,videoSendFormatFor(1080,1440,DirectPublishResolution.HD_720))
        assertEquals(1280 to 576,videoSendFormatFor(2400,1080,DirectPublishResolution.HD_720))
        assertEquals(720 to 1280,videoSendFormatFor(720,1280,DirectPublishResolution.FHD))
        assertEquals(480 to 640,videoSendFormatFor(480,640,DirectPublishResolution.HD_720))
    }
}
