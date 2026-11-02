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

/** 하나의 직렬 처리기가 소유하고 닫습니다. GPU가 실패하면 보호된 CPU 픽셀로 대체합니다. */
internal class PrivacyBitmapBlur : AutoCloseable {
    private var gpu: Gpu? = null
    private var gpuUnavailable = false
    var lastUsedGpu = false
        private set

    fun apply(source: Bitmap, sigma: Int = PrivacyBroadcastBlur.sigma(source.width, source.height)): Bitmap {
        lastUsedGpu = false
        if (Build.VERSION.SDK_INT >= 31 && !gpuUnavailable) {
            try {
                if (gpu?.width != source.width || gpu?.height != source.height) {
                    gpu?.close()
                    gpu = null
                    gpu = Gpu(source.width, source.height)
                }
                // 강도만 바뀌면 렌더러와 버퍼는 그대로 두고 블러 효과만 바꿉니다.
                checkNotNull(gpu).sigma = sigma
                val output = checkNotNull(gpu).apply(source)
                lastUsedGpu = true
                return output
            } catch (_: Exception) {
                gpu?.close()
                gpu = null
                gpuUnavailable = true
            }
        }
        return PrivacyMaskRenderer.broadcastBlur(source, sigma)
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
            } catch (error: Exception) { close(); throw error }
        }

        /** HWUI 반경 변환을 거쳐 GPU 그래프·iOS와 같은 전체 해상도 sigma를 씁니다. */
        var sigma = 0
            set(value) {
                if (field == value) return
                val radius = PrivacyBroadcastBlur.renderEffectRadius(value)
                node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                field = value
            }

        fun apply(source: Bitmap): Bitmap {
            // iOS broadcastStyleBlur와 같이 면적 평균으로 축소한 뒤 넓은 가우시안을 한 번 적용하며, 블록 격자는 쓰지 않습니다.
            val reduced = PrivacyMaskRenderer.reduce(source)
            try {
                val canvas = node.beginRecording()
                try {
                    canvas.drawBitmap(reduced, null, Rect(0, 0, width, height),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                } finally { node.endRecording() }
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                checkNotNull(reader.acquireNextImage()).use { image ->
                    checkNotNull(image.hardwareBuffer).use { buffer ->
                        val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer,
                            ColorSpace.get(ColorSpace.Named.SRGB)))
                        try {
                            // GPU 이미지를 닫기 전에 복사합니다. 호출자는 독립된 CPU 비트맵을 소유합니다.
                            return checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                        } finally { hardware.recycle() }
                    }
                }
            } finally { node.discardDisplayList(); reduced.recycle() }
        }

        override fun close() { renderer.destroy(); node.discardDisplayList(); reader.close() }
    }
}
