package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer

/** Explicit device experiment; defaults are selected only after checking speed AND output parity. */
@RunWith(AndroidJUnit4::class)
class PrivacyExecutionProviderDeviceTest {
    @Test fun compareDetectorAndRecognizerProviders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = OrtEnvironment.getEnvironment()
        Log.i(TAG, "available=${OrtEnvironment.getAvailableProviders()}")
        val bytes = context.assets.open("privacy-detector.onnx").use { it.readBytes() }
        val input = FloatArray(3 * 640 * 640) { ((it * 13) % 255) / 255f }
        var reference: List<FloatArray>? = null
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, 640, 640)).use { tensor ->
            for (configuration in listOf(PrivacyOrtConfiguration.DEFAULT, PrivacyOrtConfiguration.CPU_EIGHT,
                PrivacyOrtConfiguration.NNAPI_FOUR, PrivacyOrtConfiguration.NNAPI_ALLOW_CPU)) {
                try {
                    val loaded = System.nanoTime()
                    configuration.options().use { options -> env.createSession(bytes, options) }.use { session ->
                        Log.i(TAG, "detector=$configuration load_ms=${(System.nanoTime() - loaded) / 1e6}")
                        repeat(5) { sample ->
                            val started = System.nanoTime()
                            session.run(mapOf("images" to tensor)).use { result ->
                                val elapsed = (System.nanoTime() - started) / 1e6
                                val outputs = listOf("output0", "output1").map { name ->
                                    val buffer = (result[name].orElseThrow() as OnnxTensor).floatBuffer
                                    FloatArray(buffer.remaining()).also(buffer::get)
                                }
                                val expected = reference
                                if (expected == null) reference = outputs else {
                                    for (i in outputs.indices) {
                                        assertTrue(outputs[i].all(Float::isFinite))
                                        val error = outputs[i].indices.maxOf { kotlin.math.abs(outputs[i][it] - expected[i][it]) }
                                        assertTrue("$configuration output$i max_error=$error", error < .02f)
                                    }
                                }
                                Log.i(TAG, "detector=$configuration sample=$sample ms=$elapsed")
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (configuration == PrivacyOrtConfiguration.DEFAULT) throw error
                    Log.i(TAG, "detector=$configuration unavailable=${error.javaClass.simpleName} reason=${error.message?.take(300)}")
                }
            }
        }
        val image = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(102, 128, 153)) }
        var referenceEmbedding: FloatArray? = null
        try {
            for (configuration in listOf(PrivacyOrtConfiguration.DEFAULT)) {
                try {
                    val loaded = System.nanoTime()
                    PrivacyFaceModel(context, configuration::options).use { model ->
                        Log.i(TAG, "recognizer=$configuration load_ms=${(System.nanoTime() - loaded) / 1e6}")
                        repeat(5) { sample ->
                            val started = System.nanoTime()
                            val embedding = model.predict(image, floatArrayOf(.34f, .46f, .66f, .46f, .5f, .64f, .37f, .82f, .63f, .82f))
                            val elapsed = (System.nanoTime() - started) / 1e6
                            val expected = referenceEmbedding
                            if (expected == null) referenceEmbedding = embedding
                            val cosine = expected?.let { PrivacyFaceMath.cosine(embedding, it) } ?: 1f
                            Log.i(TAG, "recognizer=$configuration sample=$sample ms=$elapsed cosine=$cosine")
                            if (configuration != PrivacyOrtConfiguration.XNNPACK_FOUR) {
                                assertTrue("$configuration embedding mismatch", cosine >= .999f)
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (configuration == PrivacyOrtConfiguration.DEFAULT) throw error
                    Log.i(TAG, "recognizer=$configuration unavailable=${error.javaClass.simpleName}")
                }
            }
        } finally { image.recycle() }
    }

    companion object { private const val TAG = "PrivacyProviders" }
}
