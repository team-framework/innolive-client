package com.framework.innolive

import com.framework.innolive.feature.live.CameraResolution
import com.framework.innolive.feature.live.defaultCameraResolution
import com.framework.innolive.feature.live.selectedCameraResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraResolutionTest {
    @Test
    fun outputSizesCreateDistinctResolutionsInDescendingOrder() {
        assertEquals(
            listOf(
                CameraResolution(width = 1920, height = 1080),
                CameraResolution(width = 1000, height = 1000),
            ),
            CameraResolution.fromOutputSizes(
                listOf(1000 to 1000, 2560 to 1440, 1920 to 1080, 1000 to 1000),
            ),
        )
        assertEquals("1920x1080", CameraResolution(width = 1920, height = 1080).key)
        assertEquals("1920 × 1080", CameraResolution(width = 1920, height = 1080).displayName)
    }

    @Test
    fun acceptsRotatedFullHdAndRejectsLargerSizes() {
        assertEquals(
            listOf(CameraResolution(width = 1080, height = 1920)),
            CameraResolution.fromOutputSizes(listOf(1080 to 1920, 3840 to 2160)),
        )
    }
    @Test
    fun onDeviceDefaultsTo720pButExplicitChoiceRemainsAvailable() {
        val sizes = CameraResolution.fromOutputSizes(listOf(1920 to 1080, 1280 to 720, 960 to 540))
        assertEquals(CameraResolution(1280, 720), defaultCameraResolution(sizes, onDevice = true))
        assertEquals(CameraResolution(1920, 1080), defaultCameraResolution(sizes, onDevice = false))
        assertEquals(CameraResolution(1920, 1080), sizes.first { it.key == "1920x1080" })
        assertEquals(CameraResolution(1920, 1080),
            selectedCameraResolution(sizes, "1920x1080", onDevice = true))
        assertEquals(CameraResolution(1280, 720),
            selectedCameraResolution(sizes, null, onDevice = true))
        assertEquals(null, defaultCameraResolution(emptyList(), onDevice = true))
        assertEquals(CameraResolution(1920, 1080),
            defaultCameraResolution(listOf(CameraResolution(1920, 1080)), onDevice = true))
    }
}
