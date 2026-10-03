package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.EGL14
import org.webrtc.EglBase
import org.webrtc.GlUtil

/** One immutable GPU snapshot. Its shared context outlives the producing graph.
 * Pixel mapping/Bitmap copying can run on the face worker without blocking the video queue. */
internal class PrivacyFaceReadback private constructor(
    private val pool: PrivacyFaceReadbackPool, private val egl: EglBase, private val buffer: Int, private val fence: Long,
    private val width: Int, private val height: Int,
) : AutoCloseable {
    private var closed = false

    @Synchronized fun read(): Bitmap {
        check(!closed)
        egl.makeCurrent()
        var mapped = false
        var image: Bitmap? = null
        try {
            PrivacyNativeGpuFence.awaitReady(fence)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffer)
            val pixels = checkNotNull(GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER,
                0, width * height * 4, GLES30.GL_MAP_READ_BIT))
            mapped = true
            GlUtil.checkNoGLES2Error("face readback map")
            image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixels.position(0)
            image.copyPixelsFromBuffer(pixels)
            image.setHasAlpha(false)
            val valid = GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            mapped = false
            check(valid)
            return image
        } catch (error: Throwable) {
            image?.recycle()
            throw error
        } finally {
            try {
                if (mapped) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            } finally { close() }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        egl.makeCurrent()
        val replacedContext = previousContext != EGL14.eglGetCurrentContext()
        try {
            PrivacyNativeGpuFence.destroy(fence)
            GLES30.glDeleteBuffers(1, intArrayOf(buffer), 0)
        } finally {
            try { egl.detachCurrent(); pool.release() }
            finally {
                if (replacedContext && previousContext != EGL14.EGL_NO_CONTEXT)
                    check(EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext))
            }
        }
    }

    companion object {
        /** Called with the producer's current context and crop framebuffer bound. */
        fun issue(pool: PrivacyFaceReadbackPool, width: Int, height: Int): PrivacyFaceReadback {
            require(width > 0 && height > 0 && width.toLong() * height * 4 <= Int.MAX_VALUE)
            val child = pool.acquire()
            val buffers = IntArray(1)
            var fence = 0L
            try {
                GLES30.glGenBuffers(1, buffers, 0)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffers[0])
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, width * height * 4, null, GLES30.GL_STREAM_READ)
                GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                GlUtil.checkNoGLES2Error("face readback issue")
                fence = PrivacyNativeGpuFence.create()
                if (fence == 0L) GLES30.glFinish()
                return PrivacyFaceReadback(pool, child, buffers[0], fence, width, height)
            } catch (error: Throwable) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                PrivacyNativeGpuFence.destroy(fence)
                GLES30.glDeleteBuffers(1, buffers, 0)
                pool.release()
                throw error
            }
        }
    }
}

/** One context and one outstanding snapshot per graph; a pending snapshot owns the last lease. */
internal class PrivacyFaceReadbackPool(sharedContext: EglBase.Context) : AutoCloseable {
    private val egl = EglBase.create(sharedContext, EglBase.configBuilder().setOpenGlesVersion(3)
        .setSupportsPixelBuffer(true).createConfigAttributes())
    private var leased = false
    private var closed = false
    init {
        try { egl.createDummyPbufferSurface() }
        catch (error: Throwable) { egl.release(); throw error }
    }
    @Synchronized fun acquire(): EglBase {
        check(!closed && !leased) { "Face readback is closed or busy" }
        leased = true
        return egl
    }
    @Synchronized fun release() {
        check(leased)
        leased = false
        if (closed) egl.release()
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (!leased) egl.release()
    }
}
