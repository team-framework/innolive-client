package com.framework.innolive.feature.live.privacy

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Serial frame owner. Only the small, selected detections and final mask cross into Java. */
internal class PrivacyNativePostprocessor : AutoCloseable {
    val predictions: ByteBuffer = ByteBuffer.allocateDirect(38 * 8400 * 4).order(ByteOrder.nativeOrder())
    val prototypes: ByteBuffer = ByteBuffer.allocateDirect(32 * 160 * 160 * 4).order(ByteOrder.nativeOrder())
    private var handle = Native.create()
    private var ready = false
    var maskPixels: Int = 0
        private set

    fun decode(): List<PrivacySegmentation.Detection> {
        check(handle != 0L)
        ready = false
        val selected = Native.decode(handle, predictions, prototypes)
        ready = true
        return List(selected.size / 38) { index ->
            val offset = index * 38
            PrivacySegmentation.Detection(
                PrivacySegmentation.Box(selected[offset], selected[offset + 1], selected[offset + 2], selected[offset + 3]),
                selected[offset + 4], selected[offset + 5].toInt(), selected.copyOfRange(offset + 6, offset + 38))
        }
    }

    /** Protect [objects] from [PrivacyObjectTracking] instead of every decoded candidate. */
    fun select(objects: List<PrivacySegmentation.Detection>) {
        check(handle != 0L && ready)
        val values = FloatArray(objects.size * 38)
        objects.forEachIndexed { index, detection ->
            val offset = index * 38
            val box = detection.box
            values[offset] = box.left; values[offset + 1] = box.top; values[offset + 2] = box.right
            values[offset + 3] = box.bottom; values[offset + 4] = detection.score
            values[offset + 5] = detection.classId.toFloat()
            detection.coefficients.copyInto(values, offset + 6)
        }
        Native.select(handle, values, IntArray(objects.size) { objects[it].trackId },
            BooleanArray(objects.size) { objects[it].held })
    }

    fun mask(exempt: Set<Int>, timestampSeconds: Double): ByteArray {
        check(handle != 0L && ready)
        ready = false
        val output = Native.mask(handle, exempt.toIntArray(), timestampSeconds)
        maskPixels = Native.maskPixels(handle)
        return output
    }

    fun reset() {
        if (handle != 0L) Native.reset(handle)
        ready = false
        maskPixels = 0
    }

    override fun close() {
        if (handle != 0L) Native.destroy(handle)
        handle = 0L
        ready = false
    }

    private object Native {
        init { System.loadLibrary("innolive_privacy") }
        external fun create(): Long
        external fun decode(handle: Long, predictions: ByteBuffer, prototypes: ByteBuffer): FloatArray
        external fun select(handle: Long, values: FloatArray, tracks: IntArray, held: BooleanArray)
        external fun mask(handle: Long, exempt: IntArray, timestamp: Double): ByteArray
        external fun maskPixels(handle: Long): Int
        external fun reset(handle: Long)
        external fun destroy(handle: Long)
    }
}
