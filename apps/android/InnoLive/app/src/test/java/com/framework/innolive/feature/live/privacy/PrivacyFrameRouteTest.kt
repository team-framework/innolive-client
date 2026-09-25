package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFrameRouteTest {
    @Test fun modeSwitchDropsInferenceFromThePreviousRoute() {
        val route = PrivacyFrameRoute(PrivacyFrameMode.LOCAL_PROTECTED)
        val oldFrame = route.ticket()!!
        route.change(PrivacyFrameMode.SERVER)
        var delivered = 0
        assertFalse(route.deliver(oldFrame) { delivered++ })
        assertTrue(route.deliver(route.ticket()!!) { delivered++ })
        assertEquals(1, delivered)
    }

    @Test fun stopDropsAFrameAlreadyBeingProcessed() {
        val route = PrivacyFrameRoute(PrivacyFrameMode.LOCAL_PROTECTED)
        val pending = route.ticket()!!
        route.stop()
        assertNull(route.ticket())
        assertFalse(route.deliver(pending) { error("stopped frame delivered") })
    }
}
