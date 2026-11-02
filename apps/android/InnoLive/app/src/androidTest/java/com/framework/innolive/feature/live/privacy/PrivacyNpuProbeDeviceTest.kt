package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * 공식 벤더 라이브러리를 앱 전용 디렉터리에 두고 실행하는 선택 진단입니다.
 * 통과는 실행과 유한한 출력만 확인하며, 모델 정확도나 제품의 NPU 선택을 보장하지 않습니다.
 * APK에는 벤더 바이너리를 포함하지 않습니다. parity_pass를 제품의 GPU 기준과 비교합니다.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyNpuProbeDeviceTest {
    @Test fun compareQualcommNpuWithThePinnedCpuAndGpuModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "privacy-npu-probe").absolutePath
        assumeTrue("Optional Qualcomm runtime not installed", File(directory, "libLiteRtCompilerPlugin_Qualcomm.so").isFile)
        for (library in listOf("QnnHtp", "QnnIr", "QnnSaver", "QnnSystem")) {
            System.load(File(directory, "lib$library.so").absolutePath)
        }
        val started = System.nanoTime()
        Environment.create(context, mapOf(
            Environment.Option.CompilerPluginLibraryDir to directory,
            Environment.Option.DispatchLibraryDir to directory)).use { environment ->
            Log.i(TAG, "available=${environment.getAvailableAccelerators()} init_ms=${(System.nanoTime()-started)/1e6}")
            CompiledModel.create(context.assets, "privacy-detector.tflite",
                CompiledModel.Options(Accelerator.NPU, Accelerator.CPU), environment).use { npu ->
                val gpuOptions = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
                    gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                }
                CompiledModel.create(context.assets, "privacy-detector.tflite", gpuOptions).use { gpu ->
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(4)
                        options.addConfigEntry("session.intra_op.allow_spinning", "0")
                        val ort = OrtEnvironment.getEnvironment()
                        ort.createSession(context.assets.open("privacy-detector.onnx").use { it.readBytes() }, options).use { cpu ->
                            val npuInputs = npu.createInputBuffers(); val npuOutputs = npu.createOutputBuffers()
                            val gpuInputs = gpu.createInputBuffers(); val gpuOutputs = gpu.createOutputBuffers()
                            try {
                                for (pattern in 0..2) {
                                    val pixels = FloatArray(3 * 640 * 640) { index -> when (pattern) {
                                        0 -> .5f
                                        1 -> (index % 640) / 639f
                                        else -> ((index * 13L + 29L) % 255L) / 255f
                                    } }
                                    val expected = OnnxTensor.createTensor(ort, FloatBuffer.wrap(pixels), longArrayOf(1,3,640,640)).use { tensor ->
                                        cpu.run(mapOf("images" to tensor)).use { result ->
                                            listOf("output0", "output1").map { name ->
                                                val values = (result[name].orElseThrow() as OnnxTensor).floatBuffer
                                                FloatArray(values.remaining()).also(values::get)
                                            }
                                        }
                                    }
                                    npuInputs.single().writeFloat(pixels); gpuInputs.single().writeFloat(pixels)
                                    repeat(2) { npu.run(npuInputs,npuOutputs); gpu.run(gpuInputs,gpuOutputs) }
                                    var actual = emptyList<FloatArray>()
                                    val npuTimes = (0..4).map {
                                        val tick = System.nanoTime(); npu.run(npuInputs,npuOutputs)
                                        actual = npuOutputs.map { it.readFloat() }
                                        (System.nanoTime()-tick)/1e6
                                    }
                                    val gpuTimes = (0..4).map {
                                        val tick = System.nanoTime(); gpu.run(gpuInputs,gpuOutputs)
                                        gpuOutputs.forEach { it.readFloat() }
                                        (System.nanoTime()-tick)/1e6
                                    }
                                    val errors = actual.indices.map { index ->
                                        check(actual[index].size == expected[index].size && actual[index].all(Float::isFinite))
                                        actual[index].indices.maxOf { abs(actual[index][it]-expected[index][it]) }
                                    }
                                    val parity = errors[0] < .05f && errors[1] < .01f
                                    Log.i(TAG, "pattern=$pattern npu_p50_ms=${npuTimes.sorted()[2]} gpu_p50_ms=${gpuTimes.sorted()[2]} " +
                                        "detection_error=${errors[0]} prototype_error=${errors[1]} parity_pass=$parity")
                                }
                            } finally {
                                (npuInputs+npuOutputs+gpuInputs+gpuOutputs).forEach { it.close() }
                            }
                        }
                    }
                }
            }
        }
    }
    companion object { private const val TAG = "PrivacyNpuProbe" }
}
