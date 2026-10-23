package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFacePreparationGateTest {
    @Test fun preparedModelStaysReadyAndIsNeverLoadedAgain() {
        val gate = PrivacyFacePreparationGate()
        assertTrue(gate.tryBegin())
        gate.complete(success = true)

        // iOS처럼 한 번 준비한 모델은 앱이 끝날 때까지 유지하므로 다시 준비하지 않습니다.
        repeat(300) { assertFalse(gate.tryBegin()) }
        assertFalse(gate.failed)
        assertFalse(gate.allowRetry())
    }

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
