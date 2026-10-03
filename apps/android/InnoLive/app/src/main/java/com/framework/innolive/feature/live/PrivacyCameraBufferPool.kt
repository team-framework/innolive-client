package com.framework.innolive.feature.live

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/** One idle buffer; a leased crop/view keeps its storage out of the pool until its final release. */
internal class PrivacyCameraBufferPool : AutoCloseable {
    private var idle: JavaI420Buffer? = null
    private var closed = false

    @Synchronized fun acquire(width: Int, height: Int): VideoFrame.I420Buffer {
        check(!closed)
        val stored = idle
        idle = null
        val storage = if (stored?.width == width && stored.height == height) stored else {
            stored?.release()
            JavaI420Buffer.allocate(width, height)
        }
        return JavaI420Buffer.wrap(width, height, storage.dataY, storage.strideY,
            storage.dataU, storage.strideU, storage.dataV, storage.strideV) { recycle(storage) }
    }

    @Synchronized private fun recycle(storage: JavaI420Buffer) {
        if (closed || idle != null) storage.release() else idle = storage
    }

    @Synchronized override fun close() {
        closed = true
        idle?.release()
        idle = null
    }
}
