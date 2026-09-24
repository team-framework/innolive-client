package com.framework.innolive

import com.framework.innolive.feature.live.CameraLensFacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals("후면 카메라", CameraLensFacing.BACK.displayName)
        assertEquals("전면 카메라", CameraLensFacing.FRONT.displayName)
    }

    @Test
    fun previewMirroringIsEnabledOnlyForFrontCamera() {
        assertTrue(CameraLensFacing.FRONT.shouldMirrorPreview)
        assertFalse(CameraLensFacing.BACK.shouldMirrorPreview)
    }
}
