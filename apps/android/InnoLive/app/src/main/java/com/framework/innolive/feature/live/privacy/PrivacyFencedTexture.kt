package com.framework.innolive.feature.live.privacy

import android.graphics.Matrix
import org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicLong

internal object PrivacyTextureReadbackCounter {
    private val count=AtomicLong()
    fun value():Long=count.get()
    fun record() {count.incrementAndGet()}
}

/** The underlying buffer owns the fence until all derived views and consumers release it. */
internal class PrivacyFencedTexture(
    private val buffer: VideoFrame.TextureBuffer,
    private val fence: Long,
) : VideoFrame.TextureBuffer {
    override fun getWidth() = buffer.width
    override fun getHeight() = buffer.height
    override fun getUnscaledWidth() = buffer.unscaledWidth
    override fun getUnscaledHeight() = buffer.unscaledHeight
    override fun getType() = buffer.type
    override fun getTransformMatrix() = buffer.transformMatrix
    override fun getTextureId(): Int {
        PrivacyNativeGpuFence.awaitReady(fence)
        return buffer.textureId
    }
    override fun toI420(): VideoFrame.I420Buffer? {
        PrivacyTextureReadbackCounter.record()
        PrivacyNativeGpuFence.awaitReady(fence)
        return buffer.toI420()
    }
    override fun retain() = buffer.retain()
    override fun release() = buffer.release()
    override fun cropAndScale(x: Int,y: Int,width: Int,height: Int,scaledWidth: Int,scaledHeight: Int): VideoFrame.Buffer =
        PrivacyFencedTexture(buffer.cropAndScale(x,y,width,height,scaledWidth,scaledHeight) as VideoFrame.TextureBuffer,fence)
    override fun applyTransformMatrix(matrix: Matrix,width: Int,height: Int): VideoFrame.TextureBuffer =
        PrivacyFencedTexture(buffer.applyTransformMatrix(matrix,width,height),fence)
}
