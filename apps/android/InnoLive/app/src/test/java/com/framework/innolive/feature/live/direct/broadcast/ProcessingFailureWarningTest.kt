package com.framework.innolive.feature.live.direct.broadcast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingFailureWarningTest {
    @Test fun singleTransientFailureNeverShowsTheWarning() {
        val warning = ProcessingFailureWarning(showAfterMillis = 3_000, clearAfterMillis = 2_000)
        assertFalse(warning.onFailure(1_000))
        assertTrue(warning.pending)
        assertFalse(warning.check(2_999))
        assertFalse(warning.check(3_000))
        assertFalse(warning.active)
        assertFalse(warning.pending)
    }

    @Test fun continuingFailuresShowTheWarningAndRecoveryClearsIt() {
        val warning = ProcessingFailureWarning(showAfterMillis = 3_000, clearAfterMillis = 2_000)
        for (at in 0L..2_900L step 100) assertFalse(warning.onFailure(at))
        assertTrue(warning.onFailure(3_000))
        assertTrue(warning.active)
        assertFalse(warning.check(4_999))
        assertTrue(warning.check(5_000))
        assertFalse(warning.active)
        assertFalse(warning.pending)
    }

    @Test fun isolatedFailuresFarApartDoNotAccumulate() {
        val warning = ProcessingFailureWarning(showAfterMillis = 3_000, clearAfterMillis = 2_000)
        warning.onFailure(0)
        warning.onFailure(2_000)
        warning.onFailure(4_000)
        assertFalse(warning.onFailure(6_000))
        assertFalse(warning.active)
    }

    @Test fun failuresWithinTheGapKeepOneStreak() {
        val warning = ProcessingFailureWarning(showAfterMillis = 3_000, clearAfterMillis = 2_000)
        warning.onFailure(0)
        warning.onFailure(1_900)
        assertTrue(warning.onFailure(3_500))
        assertFalse(warning.check(4_000))
        assertTrue(warning.active)
    }
}
