package com.framework.innolive

import com.framework.innolive.feature.live.CameraResolution
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
}
