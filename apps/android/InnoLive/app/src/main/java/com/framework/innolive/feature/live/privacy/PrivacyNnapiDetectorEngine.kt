package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.EnumSet
import kotlin.math.abs

/** Same pinned FP32 model. NNAPI neural hardware is considered only after parity and speed checks. */
internal class PrivacyNnapiDetectorEngine private constructor(context: Context) : AutoCloseable {
    private val bytes = ByteBuffer.allocateDirect(3 * 640 * 640 * 4).order(ByteOrder.nativeOrder())
    private val tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), bytes.asFloatBuffer(), longArrayOf(1, 3, 640, 640))
    private val session: OrtSession
    init {
        try {
            val model = context.assets.open("privacy-detector.onnx").use { it.readBytes() }
            check(MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it.toInt() and 255) }
                == "8d111ad2dcb5e5fa9d709f3d11606dcd62ea6f1d4833633864b1e4cf47f13be7")
            OrtSession.SessionOptions().use { options ->
                options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                options.setIntraOpNumThreads(4)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                session = OrtEnvironment.getEnvironment().createSession(model, options)
            }
        } catch (error: Throwable) { tensor.close(); throw error }
    }
    fun predict(pixels: FloatArray): Pair<FloatArray, FloatArray> {
        bytes.asFloatBuffer().put(pixels)
        return session.run(mapOf("images" to tensor)).use { result ->
            fun output(name: String): FloatArray {
                val values = (result[name].orElseThrow() as OnnxTensor).floatBuffer
                return FloatArray(values.remaining()).also(values::get)
            }
            output("output0") to output("output1")
        }
    }
    override fun close() { tensor.close(); session.close() }

    companion object {
        fun validated(context: Context, reference: (FloatArray) -> Pair<FloatArray, FloatArray>,
                      fastest: (FloatArray) -> Pair<FloatArray, FloatArray>): PrivacyNnapiDetectorEngine? {
            val count = PrivacyNativePixels.neuralAcceleratorCount()
            Log.i("PrivacyDetector", "nnapi_neural_accelerators=$count")
            if (count == 0) return null
            var candidate: PrivacyNnapiDetectorEngine? = null
            try {
                candidate = PrivacyNnapiDetectorEngine(context)
                var baselineNs = 0L; var nnapiNs = 0L
                for (pattern in 0..2) {
                    val input = FloatArray(3 * 640 * 640) { index -> when (pattern) {
                        0 -> .5f
                        1 -> (index % 640) / 639f
                        else -> ((index * 13L + 29L) % 255L) / 255f
                    } }
                    val expected = reference(input)
                    candidate.predict(input)
                    val start = System.nanoTime()
                    val actual = candidate.predict(input)
                    nnapiNs += System.nanoTime() - start
                    fun error(a: FloatArray, b: FloatArray): Float {
                        check(a.size == b.size && PrivacyNativePixels.finiteFloats(a) && PrivacyNativePixels.finiteFloats(b))
                        var maximum = 0f
                        for (i in a.indices) maximum = maxOf(maximum, abs(a[i] - b[i]))
                        return maximum
                    }
                    check(error(actual.first, expected.first) < .05f && error(actual.second, expected.second) < .01f)
                    val baselineStart = System.nanoTime(); fastest(input); baselineNs += System.nanoTime() - baselineStart
                }
                check(nnapiNs < baselineNs * .9) { "insufficient_speed baseline_ms=${baselineNs / 3e6} nnapi_ms=${nnapiNs / 3e6}" }
                Log.i("PrivacyDetector", "nnapi_validated baseline_ms=${baselineNs / 3e6} nnapi_ms=${nnapiNs / 3e6}")
                return candidate
            } catch (error: Exception) {
                candidate?.close()
                Log.i("PrivacyDetector", "nnapi_rejected type=${error.javaClass.simpleName}")
                return null
            }
        }
    }
}
