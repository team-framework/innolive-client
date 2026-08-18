package com.framework.innolive

import com.framework.innolive.feature.live.CameraResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraResolutionTest {
    @Test
    fun outputSizesCreateDistinctResolutionsInDescendingOrder() {
        assertEquals(
            listOf(
                CameraResolution(width = 2000, height = 1000),
                CameraResolution(width = 1000, height = 1000),
            ),
            CameraResolution.fromOutputSizes(
                listOf(1000 to 1000, 2000 to 1000, 1000 to 1000),
            ),
        )
        assertEquals("2000x1000", CameraResolution(width = 2000, height = 1000).key)
        assertEquals("2000 × 1000", CameraResolution(width = 2000, height = 1000).displayName)
    }
}
