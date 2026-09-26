package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivacyFaceMathTest {
    private fun vector(a: Float, b: Float = 0f): FloatArray = FloatArray(512).apply {
        this[0] = a
        this[1] = b
    }

    @Test fun matchRequiresStrongUnambiguousSimilarity() {
        val target = vector(1f)
        val first = PrivacyRegisteredFace("first", "First", vector(1f), 0)
        val second = PrivacyRegisteredFace("second", "Second", vector(.99f, .1f), 0)
        assertEquals("first", PrivacyFaceMath.match(target, listOf(first)))
        assertNull(PrivacyFaceMath.match(target, listOf(first, second)))
        assertNull(PrivacyFaceMath.match(vector(0f, 1f), listOf(first)))
    }

    @Test fun nonFiniteAndZeroEmbeddingsCannotMatch() {
        val entry = PrivacyRegisteredFace("first", "First", vector(1f), 0)
        assertNull(PrivacyFaceMath.normalize(FloatArray(512)))
        assertNull(PrivacyFaceMath.match(vector(Float.NaN), listOf(entry)))
        assertNull(PrivacyFaceMath.match(FloatArray(10), listOf(entry)))
    }
}
