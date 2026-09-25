package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.security.MessageDigest

/** Owns the pinned YOLO ONNX session. The caller serializes access and never sends raw on error. */
internal class PrivacyOnnxModel(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        check(sha256(bytes) == MODEL_SHA256) { "Privacy model checksum mismatch" }
        val options = OrtSession.SessionOptions()
        session = try { environment.createSession(bytes, options) } finally { options.close() }
        try {
            check(session.inputNames == setOf("images"))
            check((session.outputInfo["output0"]?.info as? TensorInfo)?.shape
                ?.contentEquals(longArrayOf(1, 38, 8400)) == true)
            check((session.outputInfo["output1"]?.info as? TensorInfo)?.shape
                ?.contentEquals(longArrayOf(1, 32, 160, 160)) == true)
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    fun process(
        upright: Bitmap,
        exemptFaces: (List<PrivacySegmentation.Detection>, PrivacySegmentation.Letterbox) -> Set<Int> = { _, _ -> emptySet() },
    ): Bitmap {
        val layout = PrivacySegmentation.Letterbox(upright.width, upright.height)
        val modelInput = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(modelInput)
            canvas.drawColor(Color.rgb(114, 114, 114))
            canvas.drawBitmap(
                upright,
                Rect(0, 0, upright.width, upright.height),
                RectF(layout.left.toFloat(), layout.top.toFloat(),
                    (layout.left + layout.resizedWidth).toFloat(),
                    (layout.top + layout.resizedHeight).toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = IntArray(640 * 640)
            modelInput.getPixels(pixels, 0, 640, 0, 0, 640, 640)
            val input = FloatArray(3 * pixels.size)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                input[index] = Color.red(pixel) / 255f
                input[pixels.size + index] = Color.green(pixel) / 255f
                input[2 * pixels.size + index] = Color.blue(pixel) / 255f
            }
            val tensor = OnnxTensor.createTensor(environment, java.nio.FloatBuffer.wrap(input), longArrayOf(1, 3, 640, 640))
            try {
                session.run(mapOf("images" to tensor)).use { result ->
                    val predictions = (result["output0"].orElseThrow() as OnnxTensor).floatBuffer
                    val prototypes = (result["output1"].orElseThrow() as OnnxTensor).floatBuffer
                    val objects = PrivacySegmentation.detections(FloatArray(predictions.remaining()).also(predictions::get))
                    val exempt = exemptFaces(objects, layout)
                    val mask = PrivacySegmentation.unionMask(
                        PrivacySegmentation.protectedDetections(objects, exempt),
                        FloatArray(prototypes.remaining()).also(prototypes::get),
                    )
                    return PrivacyMaskRenderer.render(upright, mask, layout)
                }
            } finally {
                tensor.close()
            }
        } finally {
            modelInput.recycle()
        }
    }

    override fun close() { session.close() }

    companion object {
        private const val MODEL_ASSET = "privacy-detector.onnx"
        private const val MODEL_SHA256 = "8d111ad2dcb5e5fa9d709f3d11606dcd62ea6f1d4833633864b1e4cf47f13be7"
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
