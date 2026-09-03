package com.framework.innolive.feature.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationGenerationTest {
    @Test
    fun invalidatingGenerationMakesAnOlderOperationStale() {
        val generation = OperationGeneration()
        val operation = generation.begin()

        assertTrue(generation.isCurrent(operation))
        generation.invalidate()

        assertFalse(generation.isCurrent(operation))
    }

    @Test
    fun onlyTheLatestOperationIsCurrent() {
        val generation = OperationGeneration()
        val first = generation.begin()
        val second = generation.begin()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }
}
