package com.framework.innolive.feature.live

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/** Up to [maxIdle] idle buffers; a leased crop/view keeps its storage out of the pool until its final release. */
internal class PrivacyCameraBufferPool(
    /** 돌려받은 버퍼를 몇 개까지 다시 쓸지입니다. 녹화처럼 다른 스레드가 프레임을 잠시 붙잡으면 늘립니다. */
    private val maxIdle: Int = 1,
) : AutoCloseable {
    private val idle = ArrayDeque<JavaI420Buffer>()
    private var closed = false

    @Synchronized fun acquire(width: Int, height: Int): VideoFrame.I420Buffer {
        check(!closed)
        var storage: JavaI420Buffer? = null
        while (storage == null && idle.isNotEmpty()) {
            val stored = idle.removeLast()
            if (stored.width == width && stored.height == height) storage = stored else stored.release()
        }
        val target = storage ?: JavaI420Buffer.allocate(width, height)
        return JavaI420Buffer.wrap(width, height, target.dataY, target.strideY,
            target.dataU, target.strideU, target.dataV, target.strideV) { recycle(target) }
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
