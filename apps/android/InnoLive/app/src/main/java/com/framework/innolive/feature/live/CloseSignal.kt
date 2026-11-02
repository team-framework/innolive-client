package com.framework.innolive.feature.live

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** close가 시작되면 블로킹 중인 owner 작업이 바로 멈출 수 있게 하는 일회성 신호입니다. */
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
