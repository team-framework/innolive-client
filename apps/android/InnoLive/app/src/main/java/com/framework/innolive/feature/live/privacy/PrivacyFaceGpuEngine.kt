package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.security.MessageDigest

/** Created, used and destroyed on the single face worker thread. */
internal class PrivacyFaceGpuEngine private constructor(private val model: CompiledModel) : AutoCloseable {
    private var inputs = emptyList<TensorBuffer>()
    private var outputs = emptyList<TensorBuffer>()
    private val owner = Thread.currentThread()

    init {
        try {
            check(model.getInputTensorType("args_0").layout?.dimensions == listOf(1, 3, 112, 112))
            check(model.getInputTensorType("args_1").layout?.dimensions == listOf(1, 5, 2))
            inputs = model.createInputBuffers()
            outputs = model.createOutputBuffers()
            check(inputs.size == 2 && outputs.size == 1)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun predict(image: FloatArray, landmarks: FloatArray): FloatArray {
        check(Thread.currentThread() === owner)
        require(image.size == 3 * 112 * 112 && landmarks.size == 10)
        inputs[0].writeFloat(image)
        inputs[1].writeFloat(landmarks)
        model.run(inputs, outputs)
        return checkNotNull(PrivacyFaceMath.normalize(outputs.single().readFloat())) { "Invalid GPU face output" }
    }

    override fun close() {
        inputs.forEach { it.close() }; inputs = emptyList()
        outputs.forEach { it.close() }; outputs = emptyList()
        model.close()
    }

    companion object {
        const val SHA256 = "4051fa9cac8b11cc9d3949bc66401e796598f3958e4e0e9fafaaddbae5c42776"

        fun validated(context: Context, reference: (Bitmap, FloatArray) -> FloatArray): PrivacyFaceGpuEngine? {
            var candidate: PrivacyFaceGpuEngine? = null
            try {
                val options = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
                    gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM)
                }
                candidate = PrivacyFaceGpuEngine(CompiledModel.create(verifiedFile(context).absolutePath, options))
                val points = floatArrayOf(.34f, .46f, .66f, .46f, .5f, .64f, .37f, .82f, .63f, .82f)
                var cpuNs = 0L
                var gpuNs = 0L
                for (pattern in 0..2) {
                    val pixels = syntheticPixels(pattern)
                    val bitmap = Bitmap.createBitmap(pixels, 112, 112, Bitmap.Config.ARGB_8888)
                    val started = System.nanoTime()
                    val expected = try { reference(bitmap, points) } finally { bitmap.recycle() }
                    cpuNs += System.nanoTime() - started
                    val input = normalizedPixels(pixels)
                    // Discard warmup for each input before comparing speed.
                    repeat(2) { sample ->
                        val before = System.nanoTime()
                        val actual = candidate.predict(input, points)
                        if (sample == 1) gpuNs += System.nanoTime() - before
                        check(PrivacyFaceMath.cosine(expected, actual) >= .999f &&
                            actual.indices.maxOf { kotlin.math.abs(actual[it] - expected[it]) } < .01f)
                    }
                }
                check(gpuNs < cpuNs * .8) { "GPU is not faster than CPU" }
                Log.i("PrivacyFace", "gpu_validated cpu_ms=${cpuNs / 3e6} gpu_ms=${gpuNs / 3e6}")
                return candidate
            } catch (error: Exception) {
                runCatching { candidate?.close() }
                Log.i("PrivacyFace", "gpu_unavailable type=${error.javaClass.simpleName}")
                return null
            } catch (error: LinkageError) {
                runCatching { candidate?.close() }
                Log.i("PrivacyFace", "gpu_unavailable type=${error.javaClass.simpleName}")
                return null
            }
        }

        internal fun normalizedPixels(pixels: IntArray): FloatArray = FloatArray(3 * pixels.size) { i ->
            val p = pixels[i % pixels.size]
            (when (i / pixels.size) { 0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p) }) / 127.5f - 1f
        }

        internal fun syntheticPixels(pattern: Int): IntArray = IntArray(112 * 112) { index ->
            val x = index % 112; val y = index / 112
            when (pattern) {
                0 -> Color.rgb(102, 128, 153)
                1 -> Color.rgb(x * 255 / 111, y * 255 / 111, (x + y) * 255 / 222)
                else -> if ((x / 13 + y / 13) % 2 == 0) Color.rgb(51, 77, 230) else Color.rgb(204, 179, 26)
            }
        }

        private fun verifiedFile(context: Context): File {
            val target = File(context.noBackupFilesDir, "privacy-face-$SHA256.tflite")
            if (target.isFile && sha256(target) == SHA256) return target
            val temp = File.createTempFile("privacy-face-gpu-", ".tmp", context.noBackupFilesDir)
            try {
                context.assets.open("privacy-face.tflite").use { source -> temp.outputStream().use(source::copyTo) }
                check(sha256(temp) == SHA256)
                check(temp.renameTo(target))
                return target
            } finally { temp.delete() }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
