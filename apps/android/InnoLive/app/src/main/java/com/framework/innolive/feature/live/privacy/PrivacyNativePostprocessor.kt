package com.framework.innolive.feature.live.privacy

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 직렬 프레임 소유자입니다. 선택된 작은 검출 결과와 최종 마스크만 Java로 넘어옵니다. */
internal class PrivacyNativePostprocessor : AutoCloseable {
    val predictions: ByteBuffer = ByteBuffer.allocateDirect(38 * 8400 * 4).order(ByteOrder.nativeOrder())
    val prototypes: ByteBuffer = ByteBuffer.allocateDirect(32 * 160 * 160 * 4).order(ByteOrder.nativeOrder())
    private var handle = Native.create()
    private var ready = false
    /** decode한 프로토타입은 [reset] 전까지 [prototypes]에 남습니다. */
    private var decoded = false
    var maskPixels: Int = 0
        private set

    fun decode(): List<PrivacySegmentation.Detection> {
        check(handle != 0L)
        ready = false
        // 네이티브 코드는 프레임 내용 문제에만 IllegalStateException을 던지고, 버퍼·상태 오류는 IAE로 남깁니다.
        val selected = try {
            Native.decode(handle, predictions, prototypes)
        } catch (error: IllegalStateException) {
            throw PrivacyFrameContentException(error.message, error)
        }
        ready = true
        decoded = true
        return List(selected.size / 38) { index ->
            val offset = index * 38
            PrivacySegmentation.Detection(
                PrivacySegmentation.Box(selected[offset], selected[offset + 1], selected[offset + 2], selected[offset + 3]),
                selected[offset + 4], selected[offset + 5].toInt(), selected.copyOfRange(offset + 6, offset + 38))
        }
    }

    /**
     * 디코딩된 모든 후보가 아니라 [PrivacyObjectTracking]의 [objects]를 보호합니다. 검출을 건너뛴 프레임은
     * 마지막으로 decode한 프로토타입으로 예측 객체를 선택합니다.
     */
    fun select(objects: List<PrivacySegmentation.Detection>) {
        check(handle != 0L && (ready || decoded))
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
            BooleanArray(objects.size) { objects[it].repeatsMask })
        ready = true
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
        decoded = false
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
