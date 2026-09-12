package com.framework.innolive.feature.live

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A one-shot signal that lets blocking owner work stop as soon as close starts. */
internal class CloseSignal {
    private val latch = CountDownLatch(1)

    val isClosed: Boolean
        get() = latch.count == 0L

    fun close() {
        latch.countDown()
    }

    fun await(timeout: Long, unit: TimeUnit): Boolean = try {
        latch.await(timeout, unit)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        true
    }
}
