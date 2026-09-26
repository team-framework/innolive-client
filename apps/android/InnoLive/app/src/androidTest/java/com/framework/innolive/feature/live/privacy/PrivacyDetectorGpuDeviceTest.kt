package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.util.Log
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer
import kotlin.math.abs

/** Same pinned weights and input tensor; GPU failures are reported rather than mislabelled as CPU speed. */
@RunWith(AndroidJUnit4::class)
class PrivacyDetectorGpuDeviceTest {
    @Test fun convertedModelRunsOnAndroidCpuWithMatchingOutputs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pixels = FloatArray(3 * 640 * 640) { index -> (index % 640) / 639f }
        val bytes = context.assets.open("privacy-detector.onnx").use { it.readBytes() }
        val expected = OrtEnvironment.getEnvironment().createSession(bytes,
            ai.onnxruntime.OrtSession.SessionOptions()).use { reference ->
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(pixels),
                longArrayOf(1, 3, 640, 640)).use { tensor ->
                reference.run(mapOf("images" to tensor)).use { result ->
                    listOf("output0", "output1").map { name ->
                        val values = (result[name].orElseThrow() as OnnxTensor).floatBuffer
                        FloatArray(values.remaining()).also(values::get)
                    }
                }
            }
        }
        CompiledModel.create(context.assets, "privacy-detector.tflite",
            CompiledModel.Options(Accelerator.CPU)).use { model ->
            val inputs = model.createInputBuffers()
            val outputs = model.createOutputBuffers()
            try {
                inputs.single().writeFloat(pixels)
                model.run(inputs, outputs)
                for (index in expected.indices) {
                    val actual = outputs[index].readFloat()
                    assertTrue(actual.size == expected[index].size)
                    val error = actual.indices.maxOf { abs(actual[it] - expected[index][it]) }
                    assertTrue("Android LiteRT CPU output $index differs: $error", error < .005f)
                }
            } finally { inputs.forEach { it.close() }; outputs.forEach { it.close() } }
        }
    }

    @Test fun compareDetectorGpuOutputsAndLatency() {
        // The emulator's OpenGL delegate compiles the graph but cannot allocate its input buffer.
        assumeFalse("GPU tensor buffers require a physical device", Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish") || Build.MODEL.startsWith("sdk_"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val environment = OrtEnvironment.getEnvironment()
        val onnxBytes = context.assets.open("privacy-detector.onnx").use { it.readBytes() }
        OrtEnvironment.getEnvironment().createSession(onnxBytes, ai.onnxruntime.OrtSession.SessionOptions()).use { reference ->
            val options = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
                gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
            }
            CompiledModel.create(context.assets, "privacy-detector.tflite", options).use { model ->
                val input = model.createInputBuffers()
                val output = model.createOutputBuffers()
                try {
                    for (pattern in 0..2) {
                        val pixels = FloatArray(3 * 640 * 640) { index ->
                            when (pattern) {
                                0 -> .5f
                                1 -> (index % 640) / 639f
                                else -> ((index * 13L + 29L) % 255L) / 255f
                            }
                        }
                        OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, 640, 640)).use { tensor ->
                            val cpuStart = System.nanoTime()
                            val expected = reference.run(mapOf("images" to tensor)).use { result ->
                                listOf("output0", "output1").map { name ->
                                    val values = (result[name].orElseThrow() as OnnxTensor).floatBuffer
                                    FloatArray(values.remaining()).also(values::get)
                                }
                            }
                            val cpuMs = (System.nanoTime() - cpuStart) / 1e6
                            input.single().writeFloat(pixels)
                            repeat(3) { sample ->
                                val start = System.nanoTime()
                                model.run(input, output)
                                val actual = output.map { it.readFloat() }
                                val gpuMs = (System.nanoTime() - start) / 1e6
                                assertTrue(actual[0].size == expected[0].size && actual[1].size == expected[1].size)
                                val errors = actual.indices.map { index ->
                                    actual[index].indices.maxOf { abs(actual[index][it] - expected[index][it]) }
                                }
                                Log.i("PrivacyDetectorGpu", "pattern=$pattern sample=$sample cpu_ms=$cpuMs gpu_ms=$gpuMs errors=$errors")
                                assertTrue("Detector GPU differs from pinned ONNX: $errors", errors[0] < .05f && errors[1] < .01f)
                            }
                        }
                    }
                } finally { input.forEach { it.close() }; output.forEach { it.close() } }
            }
        }
    }
}
