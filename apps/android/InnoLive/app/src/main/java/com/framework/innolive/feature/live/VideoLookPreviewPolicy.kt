package com.framework.innolive.feature.live

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** One preview job at a time, at most eight accepted camera frames per second. */
internal class VideoLookPreviewGate {
    private var enabled = false
    private var closed = false
    private var generation = 0L
    private var inFlight = false
    private var lastStartedNs: Long? = null

    val isEnabled: Boolean @Synchronized get() = enabled && !closed

    @Synchronized
    fun setEnabled(value: Boolean, clear: () -> Unit) {
        if (closed || enabled == value) return
        enabled = value
        generation++
        lastStartedNs = null
        if (!value) clear()
    }

    @Synchronized
    fun tryStart(nowNs: Long): Long? {
        if (!enabled || closed || inFlight) return null
        if (lastStartedNs?.let { nowNs - it < 125_000_000L } == true) return null
        inFlight = true
        lastStartedNs = nowNs
        return generation
    }

    @Synchronized
    fun finish(ticket: Long, publish: () -> Unit) {
        inFlight = false
        if (enabled && !closed && ticket == generation) publish()
    }

    @Synchronized
    fun close(clear: () -> Unit) {
        closed = true
        enabled = false
        generation++
        clear()
    }
}

internal fun videoLookPreviewDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(1.0, 480.0 / max(width, height))
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

internal fun previewExposureLookup(targetEV: Float, appliedEV: Float): IntArray {
    val delta = (targetEV - appliedEV).takeIf(Float::isFinite)?.coerceIn(-4f, 4f) ?: 0f
    val factor = 2.0.pow(delta.toDouble())
    return IntArray(256) { (it * factor).roundToInt().coerceIn(0, 255) }
}
