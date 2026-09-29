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
    fun reset(clear: () -> Unit) {
        if (closed) return
        generation++
        lastStartedNs = null
        clear()
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
    // EV scales light, not gamma-encoded display bytes. Preserve an exact identity at 0 EV.
    if (delta == 0f) return IntArray(256) { it }
    return IntArray(256) {
        val encoded = it / 255.0
        val linear = if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
        val exposed = (linear * factor).coerceIn(0.0, 1.0)
        val output = if (exposed <= 0.0031308) exposed * 12.92 else 1.055 * exposed.pow(1.0 / 2.4) - 0.055
        (output * 255.0).roundToInt().coerceIn(0, 255)
    }
}
