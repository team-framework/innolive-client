package com.framework.innolive.feature.live

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraToolsPlacementTest {
    private fun place(pip: Rect?, maxTop: Float = 1_500f) = cameraToolsPlacement(
        naturalTop = 200f, toolsHeight = 400f, toolsRight = 1_040f, maxTop = maxTop, pipBounds = pip, gap = 30f,
    )

    @Test fun keepsNaturalPlaceWithoutPipOnTheRight() {
        assertEquals(CameraToolsPlacement(200f), place(null))
    }

    @Test fun movesBelowOverlappingPip() {
        assertEquals(CameraToolsPlacement(830f), place(Rect(Offset(740f, 220f), Size(300f, 580f))))
    }

    @Test fun keepsNaturalPlaceWhenPipIsBelowTools() {
        assertEquals(CameraToolsPlacement(200f), place(Rect(Offset(740f, 600f), Size(300f, 580f))))
    }

    @Test fun keepsNaturalPlaceWhenPipIsAboveTools() {
        assertEquals(CameraToolsPlacement(200f), place(Rect(Offset(740f, -500f), Size(300f, 600f))))
    }

    @Test fun movesBesidePipWhenThereIsNoRoomBelow() {
        // 창 왼쪽 끝(740)에서 간격(30)만큼 떨어지도록 원래 오른쪽 끝(1040)에서 330 왼쪽으로 옮깁니다.
        assertEquals(
            CameraToolsPlacement(200f, shiftLeft = 330f),
            place(Rect(Offset(740f, 500f), Size(300f, 580f)), maxTop = 700f),
        )
    }
}
