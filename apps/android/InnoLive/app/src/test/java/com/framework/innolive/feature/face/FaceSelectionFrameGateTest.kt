package com.framework.innolive.feature.face

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSelectionFrameGateTest {
    private val gate = FaceSelectionFrameGate(intervalNanos = 150_000_000L)

    @Test
    fun framesAreSpacedByTheInterval() {
        assertTrue(gate.tryBegin(1_000_000_000L))
        gate.end()

        assertFalse(gate.tryBegin(1_149_999_999L))
        assertTrue(gate.tryBegin(1_150_000_000L))
    }

    @Test
    fun onlyOneFrameIsProcessedAtATime() {
        assertTrue(gate.tryBegin(0L))

        // 감지가 간격보다 오래 걸려도 앞 프레임이 끝나기 전에는 새 프레임을 복사하지 않습니다.
        assertFalse(gate.tryBegin(1_000_000_000L))
        gate.end()
        assertTrue(gate.tryBegin(1_000_000_000L))
    }

    @Test
    fun firstFrameIsAcceptedForAnyClockOrigin() {
        assertTrue(gate.tryBegin(-5_000_000_000L))
    }

    @Test
    fun disposedGateRejectsFramesEvenWhenAFrameFinishesLater() {
        assertTrue(gate.tryBegin(0L))

        assertTrue(gate.dispose())
        assertFalse(gate.dispose())
        // 해제 직전에 맡은 프레임이 나중에 끝나도 새 프레임을 받지 않습니다.
        gate.end()

        assertTrue(gate.isDisposed)
        assertFalse(gate.tryBegin(1_000_000_000L))
    }
}
