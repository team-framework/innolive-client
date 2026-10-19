package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyMaskExpansionTest {
    @Test fun slidingWindowDilationKeepsEveryProtectedPixelAndMatchesDenseReference() {
        val size = 160
        val masks = listOf(ByteArray(size * size) { -1 }, ByteArray(size * size).apply {
            this[0] = -1; this[size * size - 1] = -1; this[80 * size + 80] = -1
            for (y in 50..100) for (x in 20..80) if ((x + y) % 7 == 0) this[y * size + x] = -1
        })
        for (mask in masks) for (radius in listOf(0, 1, 2, 4)) {
            val expected = ByteArray(mask.size)
            for (y in 0 until size) for (x in 0 until size) {
                if (mask[y * size + x].toInt() != 0) {
                    for (dy in (y - radius).coerceAtLeast(0)..(y + radius).coerceAtMost(size - 1))
                        for (dx in (x - radius).coerceAtLeast(0)..(x + radius).coerceAtMost(size - 1))
                            expected[dy * size + dx] = -1
                }
            }
            assertArrayEquals(expected, PrivacyMaskRenderer.expandedMask(mask, radius))
        }
    }

    @Test fun faceMarginFollowsTheContourWithinOneCell() {
        val size = 160
        val face = ByteArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val dx = (x - 80) / 12.0; val dy = (y - 80) / 16.0
            if (dx * dx + dy * dy <= 1) face[y * size + x] = -1
        }
        val protected = PrivacyMaskRenderer.expandedMask(face)
        for (y in 0 until size) for (x in 0 until size) {
            val nearFace = (-1..1).any { dy -> (-1..1).any { dx ->
                val nx = x + dx; val ny = y + dy
                nx in 0 until size && ny in 0 until size && face[ny * size + nx].toInt() != 0
            } }
            // Every face cell stays protected and nothing beyond the one-cell edge is added.
            assertEquals("cell $x,$y", nearFace, protected[y * size + x].toInt() != 0)
        }
        // The bounding-box corner of the oval stays unprotected, so the area is not a box.
        assertEquals(0, protected[(80 - 16) * size + (80 - 12)].toInt())
    }
}
