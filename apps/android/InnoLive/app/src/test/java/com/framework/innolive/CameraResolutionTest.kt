package com.framework.innolive

import com.framework.innolive.feature.live.CameraResolution
import com.framework.innolive.feature.live.selectCameraResolution
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
    fun highestSupportedResolutionIsFirstForDefaultSelection() {
        val resolutions = CameraResolution.fromOutputSizes(
            listOf(1280 to 720, 1920 to 1080, 960 to 540),
        )

        assertEquals(CameraResolution(1920, 1080), resolutions.firstOrNull())
    }

    @Test
    fun restoredSelectionIsKeptWhenTheLensSupportsIt() {
        val resolutions = listOf(CameraResolution(1920, 1080), CameraResolution(1280, 720))

        assertEquals(CameraResolution(1280, 720), selectCameraResolution(resolutions, "1280x720"))
    }

    @Test
    fun largestResolutionReplacesOnlyAnUnsupportedOrMissingSelection() {
        val frontResolutions = listOf(CameraResolution(1280, 720), CameraResolution(640, 480))

        assertEquals(CameraResolution(1280, 720), selectCameraResolution(frontResolutions, "1920x1080"))
        assertEquals(CameraResolution(1280, 720), selectCameraResolution(frontResolutions, null))
    }

    @Test
    fun fallbackDoesNotReplaceSelectionAcrossLensRoundTrip() {
        val backResolutions = listOf(CameraResolution(1920, 1080), CameraResolution(1280, 720))
        val frontResolutions = listOf(CameraResolution(1280, 720), CameraResolution(640, 480))
        val selectedKey = "1920x1080"

        assertEquals(CameraResolution(1920, 1080), selectCameraResolution(backResolutions, selectedKey))
        assertEquals(CameraResolution(1280, 720), selectCameraResolution(frontResolutions, selectedKey))
        assertEquals(CameraResolution(1920, 1080), selectCameraResolution(backResolutions, selectedKey))
    }

    @Test
    fun noResolutionIsChosenBeforeTheSupportedListIsRead() {
        assertEquals(null, selectCameraResolution(null, "1280x720"))
        assertEquals(null, selectCameraResolution(emptyList(), "1280x720"))
    }
}
