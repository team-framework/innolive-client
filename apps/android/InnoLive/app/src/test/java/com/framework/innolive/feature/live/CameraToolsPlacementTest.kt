package com.framework.innolive.feature.live

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraToolsPlacementTest {
    private fun top(pip: Rect?, maxTop: Float = 1_500f) =
        cameraToolsTop(naturalTop = 200f, toolsHeight = 400f, maxTop = maxTop, pipBounds = pip, gap = 30f)

    @Test fun keepsNaturalTopWithoutPipOnTheRight() {
        assertEquals(200f, top(null))
    }

    @Test fun movesBelowOverlappingPip() {
        assertEquals(830f, top(Rect(Offset(700f, 220f), Size(300f, 580f))))
    }

    @Test fun keepsNaturalTopWhenPipIsBelowTools() {
        assertEquals(200f, top(Rect(Offset(700f, 600f), Size(300f, 580f))))
    }

    @Test fun keepsNaturalTopWhenPipIsAboveTools() {
        assertEquals(200f, top(Rect(Offset(700f, -500f), Size(300f, 600f))))
    }

    @Test fun staysInPlaceWhenThereIsNoRoomBelowPip() {
        assertEquals(200f, top(Rect(Offset(700f, 220f), Size(300f, 580f)), maxTop = 700f))
    }
}
