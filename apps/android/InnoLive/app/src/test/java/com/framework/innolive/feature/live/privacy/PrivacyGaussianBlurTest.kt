package com.framework.innolive.feature.live.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyGaussianBlurTest {
    @Test fun constantColorAndOpaqueEdgesArePreserved() {
        val pixels = IntArray(7 * 3) { 0xff72ab34.toInt() }
        assertArrayEquals(pixels, PrivacyGaussianBlur.apply(pixels, 7, 3, 6.0))
        assertArrayEquals(intArrayOf(-1), PrivacyGaussianBlur.apply(intArrayOf(-1), 1, 1, 6.0))
    }

    @Test fun gaussianSmoothsBlockBoundaryWithoutWrappingOppositeEdge() {
        val pixels = IntArray(33 * 9) { if (it % 33 < 16) 0xff000000.toInt() else -1 }
        val blurred = PrivacyGaussianBlur.apply(pixels, 33, 9, 1.5)
        assertTrue((blurred[4 * 33 + 15] and 255) in 1..127)
        assertTrue((blurred[4 * 33 + 16] and 255) in 128..254)
        assertEquals(0, blurred[4 * 33] and 255)
        assertEquals(255, blurred[4 * 33 + 32] and 255)
        assertTrue(blurred.all { it ushr 24 == 255 })
    }

    @Test fun scratchReuseDoesNotChangeEarlierOutputAcrossDifferentFrameSizes() {
        val first = IntArray(19*13) {0xff000000.toInt() or ((it*37%256) shl 16) or (it*13%256)}
        val expected = PrivacyGaussianBlur.apply(first,19,13,1.5)
        val held = expected.copyOf()
        PrivacyGaussianBlur.apply(IntArray(4*7) {if(it%2==0) -1 else 0xff000000.toInt()},4,7,6.0)
        assertArrayEquals(held,expected)
        assertArrayEquals(held,PrivacyGaussianBlur.apply(first,19,13,1.5))
    }
}
