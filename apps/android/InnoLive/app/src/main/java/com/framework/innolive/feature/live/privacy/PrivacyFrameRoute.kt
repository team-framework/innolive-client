package com.framework.innolive.feature.live.privacy

internal enum class PrivacyFrameMode { SERVER, LOCAL_PROTECTED, LOCAL_RAW }

/** A mode switch invalidates an inference result captured under the previous route. */
internal class PrivacyFrameRoute(initial: PrivacyFrameMode) {
    data class Ticket(val generation: Long, val mode: PrivacyFrameMode)
    private val lock = Any()
    private var generation = 0L
    private var mode = initial
    private var stopped = false

    fun ticket(): Ticket? = synchronized(lock) {
        if (stopped) null else Ticket(generation, mode)
    }

    fun change(next: PrivacyFrameMode) = synchronized(lock) {
        generation++
        mode = next
    }

    fun invalidate() = synchronized(lock) { generation++ }

    fun stop() = synchronized(lock) {
        generation++
        stopped = true
    }

    fun deliver(ticket: Ticket, block: () -> Unit): Boolean = synchronized(lock) {
        if (stopped || ticket.generation != generation) false
        else {
            block()
            true
        }
    }
}
