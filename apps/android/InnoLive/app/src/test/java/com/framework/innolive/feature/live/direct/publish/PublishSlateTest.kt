package com.framework.innolive.feature.live.direct.publish

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishSlateTest {
    @Test fun cropFillsWiderTargetFromCenter() {
        assertEquals(SlateCrop(0, 0, 1920, 1080), slateCropRect(1920, 1080, 1280, 720))
        // 세로 이미지를 가로 출력에 채우면 가운데 띠를 씁니다.
        assertEquals(SlateCrop(0, 656, 1080, 607), slateCropRect(1080, 1920, 1280, 720))
        assertEquals(SlateCrop(656, 0, 607, 1080), slateCropRect(1920, 1080, 720, 1280))
    }

    @Test fun argbConvertsToLimitedRangeI420() {
        val black = argbToI420(IntArray(4) { 0xff000000.toInt() }, 2, 2)
        assertEquals(16, black.y[0].toInt() and 0xff)
        assertEquals(128, black.u[0].toInt() and 0xff)
        val white = argbToI420(IntArray(4) { 0xffffffff.toInt() }, 2, 2)
        assertEquals(235, white.y[0].toInt() and 0xff)
        assertEquals(128, white.v[0].toInt() and 0xff)
    }

    @Test fun oddSizesRoundChromaUp() {
        val planes = argbToI420(IntArray(15), 5, 3)
        assertEquals(3 * 2, planes.u.size)
    }
}
