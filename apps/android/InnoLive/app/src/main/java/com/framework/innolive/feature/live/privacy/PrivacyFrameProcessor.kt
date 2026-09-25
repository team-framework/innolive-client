package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/** Processes each capture synchronously so CameraX drops overdue input instead of queuing raw frames. */
internal class PrivacyFrameProcessor(context: Context) : AutoCloseable {
    private val model = PrivacyOnnxModel(context.applicationContext)
    private val faces = PrivacyFaceCoordinator(context.applicationContext)

    fun resetFaceExceptions() { faces.reset() }

    fun process(frame: VideoFrame): VideoFrame {
        val source = checkNotNull(frame.buffer.toI420()) { "Camera frame could not be converted to I420" }
        try {
            val sensor = i420ToBitmap(source)
            val rotation = frame.rotation
            require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270)
            val upright = rotate(sensor, rotation)
            if (upright !== sensor) sensor.recycle()
            try {
                val protected = model.process(upright) { objects, layout ->
                    faces.exceptions(upright, objects, layout, frame.timestampNs)
                }
                try {
                    val restored = rotate(protected, (360 - rotation) % 360)
                    if (restored !== protected) protected.recycle()
                    try {
                        check(restored.width == frame.buffer.width && restored.height == frame.buffer.height)
                        return VideoFrame(bitmapToI420(restored), rotation, frame.timestampNs)
                    } finally {
                        restored.recycle()
                    }
                } finally {
                    if (!protected.isRecycled) protected.recycle()
                }
            } finally {
                upright.recycle()
            }
        } finally {
            source.release()
        }
    }

    override fun close() {
        faces.reset()
        model.close()
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    private fun i420ToBitmap(buffer: VideoFrame.I420Buffer): Bitmap {
        val width = buffer.width
        val height = buffer.height
        val pixels = IntArray(width * height)
        val yPlane = buffer.dataY
        val uPlane = buffer.dataU
        val vPlane = buffer.dataV
        for (y in 0 until height) for (x in 0 until width) {
            val luminance = (yPlane.get(y * buffer.strideY + x).toInt() and 0xff) - 16
            val chromaU = (uPlane.get((y / 2) * buffer.strideU + x / 2).toInt() and 0xff) - 128
            val chromaV = (vPlane.get((y / 2) * buffer.strideV + x / 2).toInt() and 0xff) - 128
            val c = 298 * luminance.coerceAtLeast(0)
            val red = ((c + 409 * chromaV + 128) shr 8).coerceIn(0, 255)
            val green = ((c - 100 * chromaU - 208 * chromaV + 128) shr 8).coerceIn(0, 255)
            val blue = ((c + 516 * chromaU + 128) shr 8).coerceIn(0, 255)
            pixels[y * width + x] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun bitmapToI420(bitmap: Bitmap): JavaI420Buffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = JavaI420Buffer.allocate(width, height)
        val yPlane = output.dataY
        val uPlane = output.dataU
        val vPlane = output.dataV
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            val luminance = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
            yPlane.put(y * output.strideY + x, luminance.coerceIn(16, 235).toByte())
        }
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            var sumU = 0
            var sumV = 0
            var count = 0
            for (dy in 0..1) for (dx in 0..1) {
                if (y + dy >= height || x + dx >= width) continue
                val pixel = pixels[(y + dy) * width + x + dx]
                val red = (pixel shr 16) and 0xff
                val green = (pixel shr 8) and 0xff
                val blue = pixel and 0xff
                sumU += ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                sumV += ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                count++
            }
            val offsetU = y / 2 * output.strideU + x / 2
            val offsetV = y / 2 * output.strideV + x / 2
            uPlane.put(offsetU, (sumU / count).coerceIn(16, 240).toByte())
            vPlane.put(offsetV, (sumV / count).coerceIn(16, 240).toByte())
        }
        return output
    }
}
