package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual parity/latency experiment on unchanged weights; no person's image is captured. */
@RunWith(AndroidJUnit4::class)
class PrivacyFaceGpuDeviceTest {
    @Test fun compareUnchangedFaceWeightsOnCpuAndGpu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val points = floatArrayOf(.34f, .46f, .66f, .46f, .5f, .64f, .37f, .82f, .63f, .82f)
        PrivacyFaceModel(context, allowGpu = false).use { reference ->
            for (accelerator in listOf(Accelerator.GPU)) {
                val started = System.nanoTime()
                val options = CompiledModel.Options(accelerator, Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
                    gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM)
                }
                CompiledModel.create(context.assets, "privacy-face.tflite", options).use { model ->
                    Log.i(TAG, "accelerator=$accelerator load_ms=${(System.nanoTime() - started) / 1e6}")
                    val inputs = model.createInputBuffers()
                    val outputs = model.createOutputBuffers()
                    try {
                        for (pattern in 0..2) {
                            val pixels = IntArray(112 * 112) { index ->
                                val x = index % 112; val y = index / 112
                                when (pattern) {
                                    0 -> Color.rgb(102, 128, 153)
                                    1 -> Color.rgb(x * 255 / 111, y * 255 / 111, (x + y) * 255 / 222)
                                    else -> if ((x / 13 + y / 13) % 2 == 0) Color.rgb(51, 77, 230) else Color.rgb(204, 179, 26)
                                }
                            }
                            val bitmap = Bitmap.createBitmap(pixels, 112, 112, Bitmap.Config.ARGB_8888)
                            val expected = try { reference.predict(bitmap, points) } finally { bitmap.recycle() }
                            val image = FloatArray(3 * pixels.size) { i ->
                                val p = pixels[i % pixels.size]
                                (when (i / pixels.size) { 0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p) }) / 127.5f - 1f
                            }
                            inputs[0].writeFloat(image)
                            inputs[1].writeFloat(points)
                            repeat(6) { sample ->
                                val before = System.nanoTime()
                                model.run(inputs, outputs)
                                val actual = outputs.single().readFloat()
                                val ms = (System.nanoTime() - before) / 1e6
                                val cosine = PrivacyFaceMath.cosine(actual, expected)
                                val error = actual.indices.maxOf { kotlin.math.abs(actual[it] - expected[it]) }
                                Log.i(TAG, "accelerator=$accelerator pattern=$pattern sample=$sample ms=$ms cosine=$cosine max_abs=$error")
                                assertTrue("$accelerator invalid output", actual.all(Float::isFinite))
                                assertTrue("$accelerator cosine=$cosine max_abs=$error", cosine >= .999f && error < .01f)
                            }
                        }
                    } finally {
                        inputs.forEach { it.close() }; outputs.forEach { it.close() }
                    }
                }
            }
        }
    }

    @Test fun productionEngineSelectsGpuAfterParityAndSpeedValidation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val points = floatArrayOf(.34f, .46f, .66f, .46f, .5f, .64f, .37f, .82f, .63f, .82f)
        PrivacyFaceModel(context, allowGpu = false).use { cpu ->
            PrivacyFaceModel(context).use { accelerated ->
                assertTrue("Validated GPU was not selected on the test device", accelerated.usesGpu)
                repeat(3) { pattern ->
                    val image = Bitmap.createBitmap(PrivacyFaceGpuEngine.syntheticPixels(pattern), 112, 112, Bitmap.Config.ARGB_8888)
                    try {
                        val expected = cpu.predict(image, points)
                        val started = System.nanoTime()
                        val actual = accelerated.predict(image, points)
                        val ms = (System.nanoTime() - started) / 1e6
                        val cosine = PrivacyFaceMath.cosine(expected, actual)
                        Log.i(TAG, "production pattern=$pattern ms=$ms cosine=$cosine")
                        assertTrue(cosine >= .999f)
                    } finally { image.recycle() }
                }
            }
        }
    }
    companion object { private const val TAG = "PrivacyFaceGpu" }
}
