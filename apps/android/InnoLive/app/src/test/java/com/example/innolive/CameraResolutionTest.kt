package com.example.innolive

import com.example.innolive.feature.live.CameraResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraResolutionTest {
    @Test
    fun fullHd30ContainsCameraXRequestValues() {
        assertEquals(1920, CameraResolution.FULL_HD_30.width)
        assertEquals(1080, CameraResolution.FULL_HD_30.height)
        assertEquals(30, CameraResolution.FULL_HD_30.frameRate)
        assertEquals("1080p - 30fps", CameraResolution.FULL_HD_30.displayName)
    }
}
