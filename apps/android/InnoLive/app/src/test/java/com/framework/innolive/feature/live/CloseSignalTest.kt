package com.framework.innolive.feature.live

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseSignalTest {
    @Test
    fun closeUnblocksAWaitingRetryWithoutWaitingForTheFullDelay() {
        val signal = CloseSignal()
        val started = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        var observedClosed = false
        val waiter = thread(start = true) {
            started.countDown()
            observedClosed = signal.await(5, TimeUnit.SECONDS)
            resumed.countDown()
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(resumed.await(100, TimeUnit.MILLISECONDS))
        signal.close()

        assertTrue(resumed.await(1, TimeUnit.SECONDS))
        assertTrue(observedClosed)
        waiter.join(1_000)
    }

    @Test
    fun timeoutLeavesAnOpenSignalOpen() {
        val signal = CloseSignal()

        assertFalse(signal.await(1, TimeUnit.MILLISECONDS))
        assertFalse(signal.isClosed)
    }
}
