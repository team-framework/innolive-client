package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

internal object PrivacyFaceMath {
    fun normalize(values: FloatArray): FloatArray? {
        if (values.size != 512 || values.any { !it.isFinite() }) return null
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        if (!norm.isFinite() || norm < 0.00001f) return null
        return FloatArray(values.size) { values[it] / norm }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val first = normalize(a) ?: return -1f
        val second = normalize(b) ?: return -1f
        return first.indices.sumOf { (first[it] * second[it]).toDouble() }.toFloat()
    }

    fun match(embedding: FloatArray, entries: List<PrivacyRegisteredFace>): String? {
        if (normalize(embedding) == null) return null
        val ranked = entries.map { it.id to cosine(embedding, it.embedding) }
            .sortedByDescending { it.second }
        val first = ranked.firstOrNull() ?: return null
        if (first.second < .60f || (ranked.size > 1 && first.second - ranked[1].second < .08f)) return null
        return first.first
    }
}

/** File-backed model loading avoids retaining a second 227 MB model copy in Java memory. */
internal class PrivacyFaceModel(context: Context,
                               sessionOptions: () -> OrtSession.SessionOptions = { OrtSession.SessionOptions() }) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val detector = PrivacyYuNetModel(context.applicationContext)
    private val session: OrtSession

    init {
        val modelFile = try { verifiedModelFile(context.applicationContext) }
        catch (error: Exception) {
            detector.close()
            throw error
        }
        session = try {
            sessionOptions().use { options -> environment.createSession(modelFile.absolutePath, options) }
        } catch (error: Exception) {
            detector.close()
            throw error
        }
        try {
            check(session.inputNames == setOf("image", "landmarks"))
            check((session.inputInfo["image"]?.info as? TensorInfo)?.shape?.contentEquals(longArrayOf(1, 3, 112, 112)) == true)
            check((session.inputInfo["landmarks"]?.info as? TensorInfo)?.shape?.contentEquals(longArrayOf(1, 5, 2)) == true)
            check((session.outputInfo["embedding"]?.info as? TensorInfo)?.shape?.contentEquals(longArrayOf(1, 512)) == true)
            check(session.metadata.customMetadata["innolive.contract"] == "privacy-face-vit-kprpe-v1")
            check(session.metadata.customMetadata["innolive.checkpoint_sha256"] == CHECKPOINT_SHA256)
        } catch (error: Exception) {
            session.close()
            detector.close()
            throw error
        }
    }

    /** Null is an untrusted or unusable sample; it must never grant a blur exception. */
    fun embedding(image: Bitmap, enrollment: Boolean): FloatArray? {
        val face = detector.oneFace(image, enrollment) ?: return null
        val square = face.square()
        val cropped = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(cropped)
            canvas.drawColor(Color.BLACK)
            val scale = 112f / square.width()
            val transform = Matrix().apply {
                setScale(scale, scale)
                postTranslate(-square.left * scale, -square.top * scale)
            }
            canvas.drawBitmap(image, transform, Paint(Paint.FILTER_BITMAP_FLAG))
            return predict(cropped, face.normalizedLandmarks())
        } finally {
            cropped.recycle()
        }
    }

    /** Exposed to instrumentation so the real weights can be checked without a person's image. */
    fun predict(image: Bitmap, landmarks: FloatArray): FloatArray {
        require(image.width == 112 && image.height == 112 && landmarks.size == 10 &&
            landmarks.all(Float::isFinite))
        val pixels = IntArray(112 * 112)
        image.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val input = FloatArray(3 * pixels.size)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            input[index] = Color.red(pixel) / 127.5f - 1f
            input[pixels.size + index] = Color.green(pixel) / 127.5f - 1f
            input[2 * pixels.size + index] = Color.blue(pixel) / 127.5f - 1f
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 112, 112)).use { imageTensor ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(landmarks), longArrayOf(1, 5, 2)).use { landmarkTensor ->
                session.run(mapOf("image" to imageTensor, "landmarks" to landmarkTensor)).use { result ->
                    val output = (result["embedding"].orElseThrow() as OnnxTensor).floatBuffer
                    val values = FloatArray(output.remaining()).also(output::get)
                    return checkNotNull(PrivacyFaceMath.normalize(values)) { "Invalid face embedding" }
                }
            }
        }
    }

    override fun close() {
        session.close()
        detector.close()
    }

    private fun verifiedModelFile(context: Context): File {
        val file = File(context.noBackupFilesDir, "privacy-face-$MODEL_SHA256.onnx")
        if (file.isFile && sha256(file) == MODEL_SHA256) {
            pruneOldModels(context, file)
            return file
        }
        val temp = File(context.noBackupFilesDir, "privacy-face-$MODEL_SHA256.tmp")
        try {
            context.assets.open("privacy-face.onnx").use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
            }
            check(sha256(temp) == MODEL_SHA256) { "Face model checksum mismatch" }
            check(temp.renameTo(file)) { "Face model install failed" }
            pruneOldModels(context, file)
            return file
        } finally {
            temp.delete()
        }
    }

    private fun pruneOldModels(context: Context, current: File) {
        context.noBackupFilesDir.listFiles()?.filter {
            it != current && it.name.startsWith("privacy-face-") && it.name.endsWith(".onnx")
        }?.forEach { runCatching { it.delete() } }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val CHECKPOINT_SHA256 = "04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9"
        private const val MODEL_SHA256 = "812eeaa58ed70794dd67e1efdb1d9f614b0be18e951d2c6bc2c8876c2c34d712"
    }
}
