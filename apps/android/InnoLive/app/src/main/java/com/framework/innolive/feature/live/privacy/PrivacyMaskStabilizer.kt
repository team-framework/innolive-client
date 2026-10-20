package com.framework.innolive.feature.live.privacy

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Keeps a matched object's older edge briefly, aligned to its current box, and repeats a held track's mask. */
internal class PrivacyMaskStabilizer {
    private var previous = emptyList<PrivacySegmentation.InstanceMask>()
    private var previousTimeSeconds: Double? = null

    fun reset() {
        previous = emptyList()
        previousTimeSeconds = null
    }

    fun apply(current: List<PrivacySegmentation.InstanceMask>, atSeconds: Double): ByteArray {
        val size = PrivacySegmentation.MASK_SIZE
        require(atSeconds.isFinite() && current.all { it.bytes.size == size * size })
        val interval = previousTimeSeconds?.let { atSeconds - it } ?: MAXIMUM_GAP_SECONDS
        if (interval <= 0 || interval >= MAXIMUM_GAP_SECONDS) previous = emptyList()
        val decay = exp(-max(0.0, interval) / FADE_SECONDS).toFloat()
        val available = previous.indices.toMutableSet()
        val stabilized = current.map { instance ->
            val detection = instance.detection
            // A held track repeats its own last mask at the predicted box, like the server's one-frame mask hold.
            if (detection.held) previous.firstOrNull {
                it.detection.trackId == detection.trackId && it.detection.classId == detection.classId
            }?.let { older ->
                return@map PrivacySegmentation.InstanceMask(detection, warp(older, detection.box, ByteArray(size * size), 1f))
            }
            val match = available.filter { previous[it].detection.classId == detection.classId }
                .map { it to previous[it].detection.box.intersects(detection.box) }
                .filter { it.second >= .30f }
                .maxByOrNull { it.second }?.first
                ?: return@map instance
            available.remove(match)
            PrivacySegmentation.InstanceMask(detection, warp(previous[match], detection.box, instance.bytes.copyOf(), decay))
        }
        previous = stabilized
        previousTimeSeconds = atSeconds
        return PrivacySegmentation.union(stabilized)
    }

    /** Raises [bytes] inside [destination] to [older]'s mask scaled from its box, multiplied by [decay]. */
    private fun warp(older: PrivacySegmentation.InstanceMask, destination: PrivacySegmentation.Box,
                     bytes: ByteArray, decay: Float): ByteArray {
        val size = PrivacySegmentation.MASK_SIZE
        val source = older.detection.box
        require(destination.width > 0 && destination.height > 0)
        val scale = size.toFloat() / PrivacySegmentation.INPUT_SIZE
        val left = destination.left * scale
        val top = destination.top * scale
        val right = destination.right * scale
        val bottom = destination.bottom * scale
        val sourceLeft = source.left * scale
        val sourceTop = source.top * scale
        val sourceWidth = source.width * scale
        val sourceHeight = source.height * scale
        val x0 = max(0, floor(left).toInt())
        val x1 = min(size, ceil(right).toInt())
        val y0 = max(0, floor(top).toInt())
        val y1 = min(size, ceil(bottom).toInt())
        for (y in y0 until y1) {
            val oldY = sourceTop + (y + .5f - top) / (bottom - top) * sourceHeight - .5f
            for (x in x0 until x1) {
                val oldX = sourceLeft + (x + .5f - left) / (right - left) * sourceWidth - .5f
                val faded = (sample(older.bytes, oldX, oldY, size) * decay).toInt().coerceIn(0, 255)
                val offset = y * size + x
                if (faded > (bytes[offset].toInt() and 255)) bytes[offset] = faded.toByte()
            }
        }
        return bytes
    }

    private fun sample(bytes: ByteArray, x: Float, y: Float, size: Int): Float {
        if (x < 0 || y < 0 || x > size - 1 || y > size - 1) return 0f
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val dx = x - x0
        val dy = y - y0
        val top = (bytes[y0 * size + x0].toInt() and 255) * (1 - dx) +
            (bytes[y0 * size + x1].toInt() and 255) * dx
        val bottom = (bytes[y1 * size + x0].toInt() and 255) * (1 - dx) +
            (bytes[y1 * size + x1].toInt() and 255) * dx
        return top * (1 - dy) + bottom * dy
    }

    companion object {
        private const val FADE_SECONDS = .12
        private const val MAXIMUM_GAP_SECONDS = .20
    }
}
