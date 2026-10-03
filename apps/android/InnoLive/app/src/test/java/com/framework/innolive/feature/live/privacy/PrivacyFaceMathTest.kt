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

    @Test fun optimizedTopTwoSelectionMatchesReferenceForTwentyScaledAndInvalidEmbeddings() {
        val random=kotlin.random.Random(328)
        repeat(80) {sample ->
            val query=FloatArray(512) {random.nextFloat()*2-1}
            val entries=(0 until 20).map {index ->
                val embedding=when(index) {
                    0 -> FloatArray(512) {query[it]*(sample%7+1)}
                    1 -> if(sample%2==0)query.copyOf() else FloatArray(512) {random.nextFloat()*2-1}
                    2 -> FloatArray(512)
                    3 -> FloatArray(512) {Float.NaN}
                    else -> FloatArray(512) {random.nextFloat()*2-1}
                }
                PrivacyRegisteredFace(index.toString(),"fixture",embedding,0)
            }
            assertEquals("sample=$sample",PrivacyFaceMath.matchReference(query,entries),PrivacyFaceMath.match(query,entries))
        }
        assertNull(PrivacyFaceMath.match(vector(1f),emptyList()))
    }
}
