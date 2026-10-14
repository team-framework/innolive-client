package com.framework.innolive.feature.live
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RtpParameters.DegradationPreference
class WebRtcUplinkBitratePolicyTest {
    @Test fun serverAIBudgetExceedsTheServerEgressBitratePerResolution() {
        val fhd = requireNotNull(uplinkBitratePolicyForAI(false, DirectPublishResolution.FHD))
        assertEquals(4_000_000, fhd.startBps)
        assertEquals(6_000_000, fhd.maxBps)
        val hd = requireNotNull(uplinkBitratePolicyForAI(false, DirectPublishResolution.HD_720))
        assertEquals(2_000_000, hd.startBps)
        assertEquals(3_000_000, hd.maxBps)
        assertTrue(fhd.maxBps > 5_000_000 && hd.maxBps > 2_500_000)
        assertTrue(fhd.startBps < fhd.maxBps && hd.startBps < hd.maxBps)
    }
    @Test fun onDeviceKeepsLibraryDefaults() {
        for (resolution in DirectPublishResolution.entries) assertNull(uplinkBitratePolicyForAI(true, resolution))
        assertNull(uplinkDegradationPreferenceForAI(true))
    }
    @Test fun serverAIMaintainsResolution() {
        assertEquals(DegradationPreference.MAINTAIN_RESOLUTION, uplinkDegradationPreferenceForAI(false))
    }
}
