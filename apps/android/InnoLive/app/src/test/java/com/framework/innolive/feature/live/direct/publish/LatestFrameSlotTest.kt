package com.framework.innolive.feature.live.direct.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

class LatestFrameSlotTest {
    private class Frame(val id: Int) {
        val releases = AtomicInteger()
    }

    private val released = mutableListOf<Int>()
    private val slot = LatestFrameSlot<Frame> { frame ->
        frame.releases.incrementAndGet()
        synchronized(released) { released += frame.id }
    }

    @Test fun keepsOnlyLatestAndReleasesDisplaced() {
        assertEquals(SlotOffer.ACCEPTED, slot.offer(Frame(1)))
        assertEquals(SlotOffer.REPLACED, slot.offer(Frame(2)))
        assertEquals(2, slot.take()?.id)
        assertNull(slot.take())
        assertEquals(listOf(1), released)
    }

    @Test fun offerAfterCloseIsReleasedImmediately() {
        slot.offer(Frame(1))
        slot.close()
        assertEquals(SlotOffer.REJECTED, slot.offer(Frame(2)))
        assertNull(slot.take())
        assertEquals(listOf(1, 2), released)
    }

    @Test fun discardReleasesOnlyStillPendingItem() {
        val first = Frame(1)
        slot.offer(first)
        val taken = slot.take()
        assertSame(first, taken)
        slot.discard(first)
        assertEquals(0, first.releases.get())
        val second = Frame(2)
        slot.offer(second)
        slot.discard(second)
        assertEquals(1, second.releases.get())
        slot.close()
        assertEquals(1, second.releases.get())
    }

    @Test fun concurrentOfferAndCloseNeverLeaksOrDoubleReleases() {
        repeat(2_000) { round ->
            val frames = mutableListOf<Frame>()
            val target = LatestFrameSlot<Frame> { it.releases.incrementAndGet() }
            val barrier = CyclicBarrier(2)
            val done = CountDownLatch(2)
            Thread {
                barrier.await()
                repeat(3) { index -> Frame(round * 10 + index).also { synchronized(frames) { frames += it } }.let(target::offer) }
                done.countDown()
            }.start()
            Thread {
                barrier.await()
                target.close()
                done.countDown()
            }.start()
            done.await()
            target.close()
            synchronized(frames) { frames.forEach { assertEquals("frame ${it.id}", 1, it.releases.get()) } }
        }
    }
}
