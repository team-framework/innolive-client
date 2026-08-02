package com.example.innolive

import com.example.innolive.feature.live.CameraLensFacing
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraLensFacingTest {
    @Test
    fun supportedReturnsOnlyAvailableBuiltInCameras() {
        assertEquals(
            listOf(CameraLensFacing.BACK),
            CameraLensFacing.supported(hasBackCamera = true, hasFrontCamera = false),
        )
        assertEquals(
            listOf(CameraLensFacing.BACK, CameraLensFacing.FRONT),
            CameraLensFacing.supported(hasBackCamera = true, hasFrontCamera = true),
        )
        assertEquals("후면 카메라 (기본 기기)", CameraLensFacing.BACK.displayName)
        assertEquals("전면 카메라 (기본 기기)", CameraLensFacing.FRONT.displayName)
    }
}
