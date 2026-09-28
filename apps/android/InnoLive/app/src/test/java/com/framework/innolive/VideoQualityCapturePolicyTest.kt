package com.framework.innolive

import com.framework.innolive.feature.live.VideoQualityCapturePolicy as Policy
import com.framework.innolive.feature.live.VideoStabilizationStatus as Status
import org.junit.Assert.*
import org.junit.Test

class VideoQualityCapturePolicyTest {
    @Test fun stabilizationPrefersPreviewAndFallsBackToStandard() {
        assertEquals(2, Policy.stabilizationMode(true, setOf(0, 1, 2), true))
        assertEquals(1, Policy.stabilizationMode(true, setOf(0, 1, 2), false))
        assertEquals(1, Policy.stabilizationMode(true, setOf(0, 1), true))
        assertEquals(0, Policy.stabilizationMode(true, setOf(0), true))
        assertEquals(0, Policy.stabilizationMode(false, setOf(0, 1, 2), true))
    }

    @Test fun requestedModeAloneDoesNotProveActiveStabilization() {
        assertEquals(Status.PENDING, Policy.stabilizationStatus(true, 2, null))
        assertEquals(Status.PENDING, Policy.stabilizationStatus(true, 2, 1))
        assertEquals(Status.ACTIVE, Policy.stabilizationStatus(true, 2, 2))
        assertEquals(Status.UNSUPPORTED, Policy.stabilizationStatus(true, 2, 0))
        assertEquals(Status.UNSUPPORTED, Policy.stabilizationStatus(true, 0, null))
        assertEquals(Status.INACTIVE, Policy.stabilizationStatus(false, 0, 0))
        assertEquals(Status.PENDING, Policy.stabilizationStatus(false, 0, 1))
    }

    @Test fun exposureQuantizesToHardwareStepsWithinProductAndDeviceLimits() {
        assertEquals(2, Policy.exposureIndex(0.8f, -12, 12, 1f / 3f))
        assertEquals(6, Policy.exposureIndex(9f, -12, 12, 1f / 3f))
        assertEquals(-6, Policy.exposureIndex(-9f, -12, 12, 1f / 3f))
        assertEquals(2, Policy.exposureIndex(2f, -2, 2, 0.5f))
        assertEquals(-2..2, Policy.exposureIndices(-8, 8, 0.8f))
    }

    @Test fun invalidExposureMetadataFallsBackToNeutral() {
        assertNull(Policy.exposureIndices(2, -2, 1f))
        assertNull(Policy.exposureIndices(-2, 2, Float.NaN))
        assertNull(Policy.exposureIndices(-2, 2, 0f))
        assertEquals(0, Policy.exposureIndex(Float.NaN, -2, 2, 1f))
        assertEquals(0, Policy.exposureIndex(1f, -2, 2, 0f))
    }
}
