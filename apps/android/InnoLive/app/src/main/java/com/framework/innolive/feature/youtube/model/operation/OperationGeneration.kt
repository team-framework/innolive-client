package com.framework.innolive.feature.youtube

import java.util.concurrent.atomic.AtomicLong

/**
 * Identifies the latest asynchronous operation so a completed older request
 * cannot publish state after logout, account replacement, or recreation.
 */
internal class OperationGeneration(initialValue: Long = 0L) {
    private val value = AtomicLong(initialValue)

    val current: Long
        get() = value.get()

    fun begin(): Long = value.incrementAndGet()

    fun invalidate(): Long = value.incrementAndGet()

    fun isCurrent(operation: Long): Boolean = value.get() == operation
}
