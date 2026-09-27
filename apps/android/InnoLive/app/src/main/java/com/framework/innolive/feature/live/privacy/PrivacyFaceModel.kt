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
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

internal object PrivacyFaceMath {
    private fun norm(values: FloatArray): Float? {
        if (values.size != 512 || values.any { !it.isFinite() }) return null
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        if (!norm.isFinite() || norm < 0.00001f) return null
        return norm
    }

    fun normalize(values: FloatArray): FloatArray? {
        val norm = norm(values) ?: return null
        return FloatArray(values.size) { values[it] / norm }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val first = normalize(a) ?: return -1f
        val second = normalize(b) ?: return -1f
        return first.indices.sumOf { (first[it] * second[it]).toDouble() }.toFloat()
    }

    fun match(embedding: FloatArray, entries: List<PrivacyRegisteredFace>): String? {
        val query = normalize(embedding) ?: return null
        var bestId: String? = null
        var best = Float.NEGATIVE_INFINITY
        var second = Float.NEGATIVE_INFINITY
        for (entry in entries) {
            val length = norm(entry.embedding)
            val score = if (length == null) -1f else query.indices
                .sumOf { (query[it] * (entry.embedding[it] / length)).toDouble() }.toFloat()
            if (score > best) { second = best; best = score; bestId = entry.id }
            else if (score > second) second = score
        }
        if (best < .60f || (entries.size > 1 && best - second < .08f)) return null
        return bestId
    }

    internal fun matchReference(embedding: FloatArray, entries: List<PrivacyRegisteredFace>): String? {
        if (normalize(embedding) == null) return null
        val ranked = entries.map { it.id to cosine(embedding, it.embedding) }.sortedByDescending { it.second }
        val first = ranked.firstOrNull() ?: return null
        if (first.second < .60f || (ranked.size > 1 && first.second - ranked[1].second < .08f)) return null
        return first.first
    }
}

/** File-backed model loading avoids retaining a second 227 MB model copy in Java memory. */
internal class PrivacyFaceModel(context: Context,
                               private val sessionOptions: () -> OrtSession.SessionOptions = { OrtSession.SessionOptions() },
                               allowGpu: Boolean = true,
                               private val reuseInputs: Boolean = true) : AutoCloseable {
    private val context = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val detector = PrivacyYuNetModel(context.applicationContext, reuseInputs)
    private var session: OrtSession? = null
    private var gpu: PrivacyFaceGpuEngine? = null
    private val cropScratch by lazy { Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888) }
    private val cropCanvas by lazy { Canvas(cropScratch) }
    private val cropTransform = Matrix()
    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val inputStorage = java.nio.ByteBuffer.allocateDirect(3*112*112*4).order(java.nio.ByteOrder.nativeOrder())
    private val pixelScratch = IntArray(112*112)
    private val inputScratch = FloatArray(3*112*112)
    private val imageTensor by lazy { OnnxTensor.createTensor(environment,inputStorage.asFloatBuffer(),longArrayOf(1,3,112,112)) }
    private var imageTensorUsed = false
    private val landmarkStorage = java.nio.ByteBuffer.allocateDirect(10*4).order(java.nio.ByteOrder.nativeOrder())
    private val landmarkTensor by lazy { OnnxTensor.createTensor(environment,landmarkStorage.asFloatBuffer(),longArrayOf(1,5,2)) }
    private var landmarkTensorUsed = false
    private var cropUsed = false
    val usesGpu: Boolean get() = gpu != null

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
            val cpu = checkNotNull(session)
            check(cpu.inputNames == setOf("image", "landmarks"))
            check((cpu.inputInfo["image"]?.info as? TensorInfo)?.shape?.contentEquals(longArrayOf(1, 3, 112, 112)) == true)
            check((cpu.inputInfo["landmarks"]?.info as? TensorInfo)?.shape?.contentEquals(longArrayOf(1, 5, 2)) == true)
            check((cpu.outputInfo["embedding"]?.info as? TensorInfo)?.shape?.contentEquals(longArrayOf(1, 512)) == true)
            check(cpu.metadata.customMetadata["innolive.contract"] == "privacy-face-vit-kprpe-v1")
            check(cpu.metadata.customMetadata["innolive.checkpoint_sha256"] == CHECKPOINT_SHA256)
            if (allowGpu) {
                gpu = PrivacyFaceGpuEngine.validated(context.applicationContext, ::predict)
                if (gpu != null) { session?.close(); session = null }
            }
        } catch (error: Exception) {
            gpu?.close()
            session?.close()
            detector.close()
            throw error
        }
    }

    /** Null is an untrusted or unusable sample; it must never grant a blur exception. */
    fun embedding(image: Bitmap, enrollment: Boolean): FloatArray? = recognize(image, enrollment)?.embedding

    data class Recognition(val embedding: FloatArray, val box: RectF)

    fun recognize(image: Bitmap, enrollment: Boolean): Recognition? {
        val face = detector.oneFace(image, enrollment) ?: return null
        val square = face.square()
        val cropped = if (reuseInputs) cropScratch.also { cropUsed=true } else Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        try {
            cropped.setHasAlpha(false)
            val canvas = if (reuseInputs) cropCanvas else Canvas(cropped)
            canvas.drawColor(Color.BLACK)
            val scale = 112f / square.width()
            val transform = (if (reuseInputs) cropTransform else Matrix()).apply {
                setScale(scale, scale)
                postTranslate(-square.left * scale, -square.top * scale)
            }
            canvas.drawBitmap(image, transform, cropPaint)
            return Recognition(predict(cropped, face.normalizedLandmarks()), RectF(face.box))
        } finally {
            if (!reuseInputs) cropped.recycle()
        }
    }

    /** Exposed to instrumentation so the real weights can be checked without a person's image. */
    fun predict(image: Bitmap, landmarks: FloatArray): FloatArray {
        require(image.width == 112 && image.height == 112 && landmarks.size == 10 &&
            landmarks.all(Float::isFinite))
        val input = if (reuseInputs) {
            if (image.hasAlpha() || image.config!=Bitmap.Config.ARGB_8888) {
                image.getPixels(pixelScratch,0,112,0,0,112,112)
                PrivacyFaceGpuEngine.normalizedPixelsInto(pixelScratch,inputScratch)
                inputStorage.asFloatBuffer().put(inputScratch)
            } else {
                PrivacyNativePixels.bitmapToFaceTensor(image,inputStorage)
                inputStorage.asFloatBuffer().get(inputScratch)
            }
            inputScratch
        } else {
            val pixels = IntArray(112*112)
            image.getPixels(pixels,0,112,0,0,112,112)
            PrivacyFaceGpuEngine.normalizedPixels(pixels)
        }
        gpu?.let { accelerated ->
            try { return accelerated.predict(input, landmarks) }
            catch (error: Exception) {
                disableGpu(accelerated, error)
            } catch (error: LinkageError) { disableGpu(accelerated, error) }
        }
        val cpu = session ?: sessionOptions().use { options ->
            environment.createSession(verifiedModelFile(context).absolutePath, options)
        }.also { session = it }
        fun run(imageInput:OnnxTensor,landmarkInput:OnnxTensor):FloatArray {
            cpu.run(mapOf("image" to imageInput,"landmarks" to landmarkInput)).use { result ->
                val output = (result["embedding"].orElseThrow() as OnnxTensor).floatBuffer
                val values = FloatArray(output.remaining()).also(output::get)
                return checkNotNull(PrivacyFaceMath.normalize(values)) { "Invalid face embedding" }
            }
        }
        if (reuseInputs) {
            landmarkStorage.asFloatBuffer().put(landmarks)
            imageTensorUsed=true; landmarkTensorUsed=true
            return run(imageTensor,landmarkTensor)
        }
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),longArrayOf(1,3,112,112)).use { imageInput ->
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(landmarks),longArrayOf(1,5,2)).use { landmarkInput ->
                return run(imageInput,landmarkInput)
            }
        }
    }

    private fun disableGpu(engine: PrivacyFaceGpuEngine, error: Throwable) {
        runCatching { engine.close() }
        gpu = null
        Log.i("PrivacyFace", "gpu_fallback type=${error.javaClass.simpleName}")
    }

    override fun close() {
        if (imageTensorUsed) imageTensor.close()
        if (landmarkTensorUsed) landmarkTensor.close()
        if (cropUsed) cropScratch.recycle()
        try { gpu?.close() } finally {
            gpu = null
            try { session?.close() } finally { session = null; detector.close() }
        }
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
