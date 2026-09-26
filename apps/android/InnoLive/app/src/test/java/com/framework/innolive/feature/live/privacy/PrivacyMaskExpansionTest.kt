package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PrivacyMaskExpansionTest {
    @Test fun slidingWindowDilationKeepsEveryProtectedPixelAndMatchesDenseReference() {
        val size = 160
        val masks = listOf(ByteArray(size * size) { -1 }, ByteArray(size * size).apply {
            this[0] = -1; this[size * size - 1] = -1; this[80 * size + 80] = -1
            for (y in 50..100) for (x in 20..80) if ((x + y) % 7 == 0) this[y * size + x] = -1
        })
        for (mask in masks) for (radius in listOf(0, 2, 4)) {
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
}
