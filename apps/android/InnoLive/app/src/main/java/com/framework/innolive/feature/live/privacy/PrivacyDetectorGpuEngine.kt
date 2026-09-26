package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** The GPU is used only after this exact asset passes output and latency checks on the device. */
internal class PrivacyDetectorGpuEngine private constructor(private val model: CompiledModel) : AutoCloseable {
    private var inputs = emptyList<TensorBuffer>()
    private var outputs = emptyList<TensorBuffer>()
    private val owner = Thread.currentThread()

    init {
        try {
            inputs = model.createInputBuffers()
            outputs = model.createOutputBuffers()
            check(inputs.size == 1 && outputs.size == 2)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun predict(pixels: FloatArray): Pair<FloatArray, FloatArray> {
        check(Thread.currentThread() === owner)
        require(pixels.size == 3 * 640 * 640)
        inputs.single().writeFloat(pixels)
        model.run(inputs, outputs)
        val first = outputs[0].readFloat()
        val second = outputs[1].readFloat()
        check(first.size == 38 * 8400 && second.size == 32 * 160 * 160)
        check(PrivacyNativePixels.finiteFloats(first) && PrivacyNativePixels.finiteFloats(second))
        return first to second
    }

    override fun close() {
        inputs.forEach { it.close() }; inputs = emptyList()
        outputs.forEach { it.close() }; outputs = emptyList()
        model.close()
    }

    companion object {
        private const val TAG = "PrivacyDetector"
        private const val SHA256 = "c8c0c6b4e93a974e748cbd41a0120423180499b563812dc65dcdbc1a54b54895"

        /** CPU reference returns its outputs and inference time; tensor allocation is excluded from timing. */
        fun validated(context: Context,
                      reference: (FloatArray) -> Pair<Pair<FloatArray, FloatArray>, Long>): PrivacyDetectorGpuEngine? {
            var candidate: PrivacyDetectorGpuEngine? = null
            try {
                val options = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
                    gpuOptions = CompiledModel.GpuOptions(
                        precision = CompiledModel.GpuOptions.Precision.FP32)
                }
                candidate = PrivacyDetectorGpuEngine(CompiledModel.create(verifiedFile(context).absolutePath, options))
                var cpuNs = 0L
                var gpuNs = 0L
                for (pattern in 0..2) {
                    val pixels = FloatArray(3 * 640 * 640) { index ->
                        when (pattern) {
                            0 -> .5f
                            1 -> (index % 640) / 639f
                            else -> ((index * 13L + 29L) % 255L) / 255f
                        }
                    }
                    val (expected, elapsed) = reference(pixels)
                    cpuNs += elapsed
                    repeat(2) { sample ->
                        val started = System.nanoTime()
                        val actual = candidate.predict(pixels)
                        if (sample == 1) gpuNs += System.nanoTime() - started
                        val detectionError = maxError(actual.first, expected.first)
                        val prototypeError = maxError(actual.second, expected.second)
                        check(detectionError < .05f && prototypeError < .01f) {
                            "output_mismatch pattern=$pattern detection_error=$detectionError prototype_error=$prototypeError"
                        }
                    }
                }
                check(gpuNs < cpuNs * .8) {
                    "insufficient_speed cpu_ms=${cpuNs / 3e6} gpu_ms=${gpuNs / 3e6}"
                }
                Log.i(TAG, "gpu_validated cpu_ms=${cpuNs / 3e6} gpu_ms=${gpuNs / 3e6}")
                return candidate
            } catch (error: Exception) {
                runCatching { candidate?.close() }
                Log.i(TAG, "gpu_unavailable type=${error.javaClass.simpleName} " +
                    "detail=${error.message?.take(160)}")
                return null
            } catch (error: LinkageError) {
                runCatching { candidate?.close() }
                Log.i(TAG, "gpu_unavailable type=${error.javaClass.simpleName} " +
                    "detail=${error.message?.take(160)}")
                return null
            }
        }

        private fun maxError(actual: FloatArray, expected: FloatArray): Float {
            check(actual.size == expected.size)
            var largest = 0f
            for (index in actual.indices) {
                check(expected[index].isFinite() && actual[index].isFinite())
                largest = maxOf(largest, abs(actual[index] - expected[index]))
            }
            return largest
        }

        private fun verifiedFile(context: Context): File {
            val target = File(context.noBackupFilesDir, "privacy-detector-$SHA256.tflite")
            if (target.isFile && sha256(target) == SHA256) return target
            val temp = File.createTempFile("privacy-detector-gpu-", ".tmp", context.noBackupFilesDir)
            try {
                context.assets.open("privacy-detector.tflite").use { source ->
                    temp.outputStream().use(source::copyTo)
                }
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
