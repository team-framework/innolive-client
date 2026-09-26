package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import com.framework.innolive.BuildConfig
import org.webrtc.VideoFrame

/** Processes each capture synchronously so CameraX drops overdue input instead of queuing raw frames. */
internal class PrivacyFrameProcessor(context: Context) : AutoCloseable {
    private val model = PrivacyOnnxModel(context.applicationContext)
    private val pixels = PrivacyPixelConverter()
    private val faces = PrivacyFaceCoordinator(context.applicationContext)
    var lastTimings: PrivacyFrameTimings? = null
        private set
    private var lastLogNs = System.nanoTime()
    private val sensorPixels = BitmapScratch()
    private val uprightPixels = BitmapScratch()
    private val restoredPixels = BitmapScratch()
    private var geometry: Triple<Int, Int, Int>? = null

    fun resetFaceExceptions() { faces.reset() }

    fun process(frame: VideoFrame): VideoFrame {
        val started = System.nanoTime()
        val source = checkNotNull(frame.buffer.toI420()) { "Camera frame could not be converted to I420" }
        try {
            val rotation = frame.rotation
            require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270)
            val nextGeometry = Triple(source.width, source.height, rotation)
            if (geometry != nextGeometry) { faces.reset(); geometry = nextGeometry }
            val sensor = sensorPixels.get(source.width, source.height)
            pixels.toBitmap(source, sensor)
            val upright = rotate(sensor, rotation, uprightPixels)
            val converted = System.nanoTime()
            faces.beginFrame(upright, frame.timestampNs)
            val protected = model.process(upright) { objects, layout ->
                faces.exceptions(upright, objects, layout, frame.timestampNs)
            }
            val processed = System.nanoTime()
            try {
                val restored = rotate(protected, (360 - rotation) % 360, restoredPixels)
                check(restored.width == frame.buffer.width && restored.height == frame.buffer.height)
                val output = VideoFrame(pixels.toI420(restored), rotation, frame.timestampNs)
                val completed = System.nanoTime()
                lastTimings = PrivacyFrameTimings((converted - started) / 1e6,
                    checkNotNull(model.lastTimings), (completed - processed) / 1e6,
                    (completed - started) / 1e6)
                if (BuildConfig.DEBUG && completed - lastLogNs >= 5_000_000_000L) {
                    lastLogNs = completed
                    Log.i("PrivacyPipeline", "size=${frame.buffer.width}x${frame.buffer.height} $lastTimings")
                }
                return output
            } finally {
                protected.recycle()
            }
        } finally {
            source.release()
        }
    }

    override fun close() {
        faces.reset()
        try { model.close() } finally { sensorPixels.close(); uprightPixels.close(); restoredPixels.close() }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int, scratch: BitmapScratch): Bitmap {
        if (degrees == 0) return bitmap
        val transpose = degrees == 90 || degrees == 270
        val output = scratch.get(if (transpose) bitmap.height else bitmap.width,
            if (transpose) bitmap.width else bitmap.height)
        PrivacyBitmapRotation.draw(bitmap, degrees, output)
        return output
    }
}

/** No consumer retains these bitmaps: every outgoing VideoFrame owns separate I420 planes. */
private class BitmapScratch : AutoCloseable {
    private var bitmap: Bitmap? = null
    fun get(width: Int, height: Int): Bitmap {
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        return checkNotNull(bitmap)
    }
    override fun close() { bitmap?.recycle(); bitmap = null }
}

internal data class PrivacyFrameTimings(
    val inputMs: Double, val model: PrivacyModelTimings, val outputMs: Double, val totalMs: Double,
)

internal object PrivacyBitmapRotation {
    fun draw(bitmap: Bitmap, degrees: Int, output: Bitmap) {
        require(bitmap !== output && degrees in listOf(90, 180, 270))
        require(output.width == if (degrees == 180) bitmap.width else bitmap.height)
        require(output.height == if (degrees == 180) bitmap.height else bitmap.width)
        val matrix = Matrix().apply {
            setRotate(degrees.toFloat())
            when (degrees) {
                90 -> postTranslate(bitmap.height.toFloat(), 0f)
                180 -> postTranslate(bitmap.width.toFloat(), bitmap.height.toFloat())
                270 -> postTranslate(0f, bitmap.width.toFloat())
            }
        }
        Canvas(output).apply { drawColor(Color.BLACK); drawBitmap(bitmap, matrix, null) }
    }
}
