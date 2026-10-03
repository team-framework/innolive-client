package com.framework.innolive.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedFrameSlotTest {
    @Test fun keepsOnlyNewestPendingAndReopensAfterDrain() {
        val slot=ProtectedFrameSlot<Int>()
        assertTrue(slot.offer(1).start)
        assertFalse(slot.offer(2).start)
        assertEquals(2,slot.offer(3).displaced)
        assertEquals(3,slot.finish())
        assertNull(slot.finish())
        assertTrue(slot.offer(4).start)
        assertNull(slot.finish())
    }

    @Test fun modeChangeAndShutdownDiscardWaitingFrame() {
        val slot=ProtectedFrameSlot<Int>()
        slot.offer(1)
        slot.offer(2)
        assertEquals(2,slot.clearPending())
        assertNull(slot.finish())
        assertTrue(slot.offer(3).start)
        slot.offer(4)
        assertEquals(4,slot.abort())
        assertTrue(slot.offer(5).start)
    }
}
