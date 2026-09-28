package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test

class PreviewExposureFrameGateTest {
    @Test fun oldCameraImagesCannotUseANewerExposureValue() {
        val gate = PreviewExposureFrameGate()
        gate.begin()
        assertNull(gate.exposureForFrame(90))
        gate.record(100, 0.8f, true)
        assertNull(gate.exposureForFrame(90))
        assertEquals(0.8f, gate.exposureForFrame(100))
        assertEquals(0.8f, gate.exposureForFrame(110))
        gate.record(105, 0f, false)
        assertNull(gate.exposureForFrame(110))
        gate.record(104, 0.8f, true)
        assertNull(gate.exposureForFrame(110))
        gate.record(120, 0.8f, true)
        assertNull(gate.exposureForFrame(115))
        assertEquals(0.8f, gate.exposureForFrame(125))
    }

    @Test fun newExposureRequestMustWaitForAnotherSettledFrame() {
        val gate = PreviewExposureFrameGate()
        gate.record(100, 0f, true)
        gate.begin()
        assertNull(gate.exposureForFrame(110))
        gate.record(120, 1f, false)
        assertNull(gate.exposureForFrame(120))
        gate.record(130, 1f, true)
        assertEquals(1f, gate.exposureForFrame(130))
    }
}
