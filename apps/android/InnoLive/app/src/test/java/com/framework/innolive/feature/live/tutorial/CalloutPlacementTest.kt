package com.framework.innolive.feature.live.tutorial

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class CalloutPlacementTest {
    @Test
    fun calloutGoesAboveALowTargetWhenItFits() {
        val placement = place(highlight = Rect(0f, 800f, 300f, 860f), naturalHeight = 200)

        assertEquals(CalloutPlacement(edge = 788, alignsBottom = true, maxHeight = 780), placement)
    }

    @Test
    fun calloutGoesBelowAHighTargetWhenItFits() {
        val placement = place(highlight = Rect(0f, 100f, 300f, 160f), naturalHeight = 200)

        assertEquals(CalloutPlacement(edge = 172, alignsBottom = false, maxHeight = 828), placement)
    }

    @Test
    fun calloutUsesTheRoomierSideAndScrollsWhenNeitherSideFits() {
        // 가로 화면·큰 글꼴: 상태 패널이 화면 대부분을 차지한다.
        val placement = place(highlight = Rect(0f, 200f, 300f, 300f), naturalHeight = 500, areaBottom = 360)

        assertEquals(true, placement.alignsBottom)
        assertEquals(188, placement.edge)
        assertEquals(180, placement.maxHeight)
    }

    @Test
    fun tinySpaceStillReservesRoomForTheButtons() {
        val placement = place(
            highlight = Rect(0f, 40f, 300f, 330f),
            naturalHeight = 500,
            areaBottom = 360,
        )

        assertEquals(160, placement.maxHeight)
        assertEquals(true, placement.alignsBottom)
        // 위 끝이 화면 안에 남도록 아래 끝을 내린다.
        assertEquals(168, placement.edge)
    }

    @Test
    fun calloutWithoutTargetSitsAboveTheBottomMargin() {
        val placement = place(highlight = null, naturalHeight = 200)

        assertEquals(CalloutPlacement(edge = 976, alignsBottom = true, maxHeight = 968), placement)
    }

    private fun place(
        highlight: Rect?,
        naturalHeight: Int,
        areaBottom: Int = 1000,
    ) = calloutPlacement(
        highlight = highlight,
        naturalHeight = naturalHeight,
        areaTop = 8,
        areaBottom = areaBottom,
        spacing = 12,
        minimumHeight = 160,
        bottomMargin = 24,
    )
}
