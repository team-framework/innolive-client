package com.framework.innolive.feature.live

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * Up to [maxIdle] idle buffers; a leased crop/view keeps its storage out of the pool until its final release.
 * Frames handed to WebRTC can outlive the next capture, so senders keep more than one idle buffer.
 */
internal class PrivacyCameraBufferPool(private val maxIdle: Int = 1) : AutoCloseable {
    private val idle = ArrayDeque<JavaI420Buffer>()
    private var closed = false

    init { require(maxIdle >= 1) }

    @Synchronized fun acquire(width: Int, height: Int): VideoFrame.I420Buffer {
        check(!closed)
        var storage: JavaI420Buffer? = null
        while (storage == null && idle.isNotEmpty()) {
            val stored = idle.removeLast()
            if (stored.width == width && stored.height == height) storage = stored else stored.release()
        }
        val leased = storage ?: JavaI420Buffer.allocate(width, height)
        return JavaI420Buffer.wrap(width, height, leased.dataY, leased.strideY,
            leased.dataU, leased.strideU, leased.dataV, leased.strideV) { recycle(leased) }
    }

    @Synchronized private fun recycle(storage: JavaI420Buffer) {
        if (closed || idle.size >= maxIdle) storage.release() else idle.addLast(storage)
    }

    @Synchronized override fun close() {
        closed = true
        idle.forEach { it.release() }
        idle.clear()
    }
}
