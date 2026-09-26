package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFacePreparationGateTest {
    @Test fun failedLoadIsNotRetriedByLaterFramesUntilExplicitRetry() {
        val gate = PrivacyFacePreparationGate()
        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        gate.complete(success = false)
        assertTrue(gate.failed)
        repeat(300) { assertFalse(gate.tryBegin()) }

        assertTrue(gate.allowRetry())
        assertTrue(gate.tryBegin())
        gate.complete(success = true)
        assertFalse(gate.failed)
        assertFalse(gate.tryBegin())
        assertFalse(gate.allowRetry())
    }
}
