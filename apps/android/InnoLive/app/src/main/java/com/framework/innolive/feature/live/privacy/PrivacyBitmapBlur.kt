package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Rect
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max

/** Owned and closed by one serial processor. GPU failure falls back to protected CPU pixels. */
internal class PrivacyBitmapBlur : AutoCloseable {
    private var gpu: Gpu? = null
    private var gpuUnavailable = false
    var lastUsedGpu = false
        private set

    fun apply(source: Bitmap): Bitmap {
        lastUsedGpu = false
        if (Build.VERSION.SDK_INT >= 31 && !gpuUnavailable) {
            try {
                if (gpu?.width != source.width || gpu?.height != source.height) {
                    gpu?.close()
                    gpu = null
                    gpu = Gpu(source.width, source.height)
                }
                val output = checkNotNull(gpu).apply(source)
                lastUsedGpu = true
                return output
            } catch (_: Exception) {
                gpu?.close()
                gpu = null
                gpuUnavailable = true
            }
        }
        return PrivacyMaskRenderer.pixelatedBlur(source)
    }

    override fun close() { if (Build.VERSION.SDK_INT >= 31) gpu?.close(); gpu = null }

    @RequiresApi(31)
    private class Gpu(val width: Int, val height: Int) : AutoCloseable {
        private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        private val node = RenderNode("PrivacyGaussianBlur")
        private val renderer = HardwareRenderer()

        init {
            try {
                renderer.setSurface(reader.surface)
                renderer.setContentRoot(node)
                node.setPosition(0, 0, width, height)
                node.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
            } catch (error: Exception) { close(); throw error }
        }

        fun apply(source: Bitmap): Bitmap {
            val small = Bitmap.createBitmap(max(1, width / 24), max(1, height / 24), Bitmap.Config.ARGB_8888)
            try {
                android.graphics.Canvas(small).drawBitmap(source, null, Rect(0, 0, small.width, small.height),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                val canvas = node.beginRecording()
                try { canvas.drawBitmap(small, null, Rect(0, 0, width, height), null) }
                finally { node.endRecording() }
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                checkNotNull(reader.acquireNextImage()).use { image ->
                    checkNotNull(image.hardwareBuffer).use { buffer ->
                        val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer,
                            ColorSpace.get(ColorSpace.Named.SRGB)))
                        try {
                            // Copy before closing the GPU image; the caller owns an independent CPU bitmap.
                            return checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                        } finally { hardware.recycle() }
                    }
                }
            } finally { node.discardDisplayList(); small.recycle() }
        }

        override fun close() { renderer.destroy(); node.discardDisplayList(); reader.close() }
    }
}
