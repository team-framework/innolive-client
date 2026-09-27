package com.framework.innolive.feature.live.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PrivacyGpuInputDeviceTest {
    @Test fun inspectSupportedNativeOutputBuffers() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        PrivacyGpuFramePipeline(null,useGles3=true).use { graph ->
            graph.createNativeInputModel(PrivacyDetectorGpuEngine.verifiedFile(context).absolutePath)
            val outputs=graph.nativeOutputBufferTypes()
            assertEquals(2,outputs.size)
            outputs.forEachIndexed { index,types ->
                assertTrue("Runtime did not expose supported output buffers",types.size>1)
                assertTrue("Managed output is not supported",types.drop(1).contains(types[0]))
                Log.i("PrivacyStages","stage=gpu_output_buffers output=$index selected=${types[0]} supported=${types.drop(1)}")
            }
        }
    }
    @Test fun validationRestoresFirstFrameAndFallbackReadsCurrentFrame() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val source=JavaI420Buffer.allocate(640,480)
        val options=CompiledModel.Options(Accelerator.GPU,Accelerator.CPU).apply {
            gpuOptions=CompiledModel.GpuOptions(precision=CompiledModel.GpuOptions.Precision.FP32)
        }
        try {
            CompiledModel.create(PrivacyDetectorGpuEngine.verifiedFile(context).absolutePath,options).use { reference ->
                val inputs=reference.createInputBuffers();val outputs=reference.createOutputBuffers()
                try {
                    PrivacyGpuFramePipeline(null,useGles3=true).use { graph ->
                        repeat(source.dataY.capacity()) { source.dataY.put(it,90.toByte()) }
                        repeat(source.dataU.capacity()) { source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte()) }
                        graph.prepare(source,0)
                        val first=IntArray(640*640).also { graph.modelBitmap.getPixels(it,0,640,0,0,640,640) }
                        graph.validateNativeInput(context) { pixels ->
                            inputs.single().writeFloat(pixels);reference.run(inputs,outputs)
                            outputs[0].readFloat() to outputs[1].readFloat()
                        }
                        val restored=IntArray(first.size).also { graph.modelBitmap.getPixels(it,0,640,0,0,640,640) }
                        assertArrayEquals(first,restored)
                        assertTrue("Validated GL input was rejected",graph.nativeInputEnabled)
                        assertTrue(graph.nativeInputUsesManagedSync)
                        val before = graph.predictNativeInput()
                        val predictions = ByteBuffer.allocateDirect(38*8400*4).order(ByteOrder.nativeOrder())
                        val prototypes = ByteBuffer.allocateDirect(32*160*160*4).order(ByteOrder.nativeOrder())
                        graph.predictNativeInputInto(predictions,prototypes)
                        val directPredictions=FloatArray(38*8400).also {predictions.asFloatBuffer().get(it)}
                        val directPrototypes=FloatArray(32*160*160).also {prototypes.asFloatBuffer().get(it)}
                        assertArrayEquals(before.first,directPredictions,.00001f)
                        assertArrayEquals(before.second,directPrototypes,.00001f)
                        Log.i("PrivacyStages","stage=gpu_input production_enabled=${graph.nativeInputEnabled}")
                        repeat(source.dataY.capacity()) { source.dataY.put(it,200.toByte()) }
                        graph.prepare(source,0,readModel=false)
                        graph.disableNativeInput()
                        assertFalse(graph.nativeInputEnabled)
                        val current=graph.modelBitmap.getPixel(320,320)
                        assertTrue(android.graphics.Color.red(current)>180)
                        graph.validateNativeInput(context) { error("Failed input must not reload each frame") }
                        assertFalse(graph.nativeInputEnabled)
                    }
                } finally { (inputs+outputs).forEach { it.close() } }
            }
        } finally {source.release()}
    }
    @Test fun managedInteropMatchesGlFinishAcrossChangingFrames() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val source=JavaI420Buffer.allocate(640,480)
        try {
            repeat(source.dataU.capacity()) {source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte())}
            PrivacyGpuFramePipeline(null,useGles3=true).use {graph ->
                graph.createNativeInputModel(PrivacyDetectorGpuEngine.verifiedFile(context).absolutePath)
                val blocking=mutableListOf<Double>();val managed=mutableListOf<Double>()
                repeat(24) {index ->
                    repeat(source.dataY.capacity()) {source.dataY.put(it,(if(index%2==0)32 else 200).toByte())}
                    fun run(fence:Boolean):Pair<FloatArray,FloatArray> {
                        val tick=System.nanoTime();graph.prepare(source,0,readModel=false)
                        val result=graph.predictNativeInput(fence)
                        assertEquals(fence,graph.nativeInputUsesManagedSync)
                        (if(fence)managed else blocking).add((System.nanoTime()-tick)/1e6)
                        return result
                    }
                    val first=run(index%2==0);val second=run(index%2!=0)
                    val predictionError=first.first.indices.maxOf {abs(first.first[it]-second.first[it])}
                    val maskError=first.second.indices.maxOf {abs(first.second[it]-second.second[it])}
                    assertTrue("Input synchronization mismatch $predictionError/$maskError",predictionError<.05f && maskError<.01f)
                }
                Log.i("PrivacyStages","stage=input_sync gl_finish_p50_ms=${blocking.drop(4).sorted()[10]} managed_interop_p50_ms=${managed.drop(4).sorted()[10]} changing_frames=24")
            }
        } finally {source.release()}
    }

    @Test fun compareNativeGpuInputWithBitmapFloatInput() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val file=File(context.cacheDir,"privacy-gpu-input-probe.tflite")
        context.assets.open("privacy-detector.tflite").use { source -> file.outputStream().use(source::copyTo) }
        val source=JavaI420Buffer.allocate(640,480)
        val floats=ByteBuffer.allocateDirect(3*640*640*4).order(ByteOrder.nativeOrder())
        val options=CompiledModel.Options(Accelerator.GPU,Accelerator.CPU).apply {
            gpuOptions=CompiledModel.GpuOptions(precision=CompiledModel.GpuOptions.Precision.FP32)
        }
        try {
            CompiledModel.create(file.absolutePath,options).use { reference ->
                val inputs=reference.createInputBuffers(); val outputs=reference.createOutputBuffers()
                try {
                    PrivacyGpuFramePipeline(null,useGles3=true).use { graph ->
                        graph.createNativeInputModel(file.absolutePath)
                        for(pattern in 0..2) {
                            for(y in 0 until 480) for(x in 0 until 640) source.dataY.put(y*source.strideY+x,
                                when(pattern) { 0->128; 1->16+x*219/639; else->16+(x*13+y*19)%219 }.toByte())
                            repeat(source.dataU.capacity()) { source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte()) }
                            graph.prepare(source,0)
                            PrivacyNativePixels.bitmapToTensor(graph.modelBitmap,floats)
                            val input=FloatArray(3*640*640).also { floats.asFloatBuffer().get(it) }
                            inputs.single().writeFloat(input); reference.run(inputs,outputs)
                            val expected=outputs.map { it.readFloat() }
                            val actual=graph.predictNativeInput().let { listOf(it.first,it.second) }
                            val errors=actual.indices.map { index ->
                                assertEquals(expected[index].size,actual[index].size)
                                assertTrue(actual[index].all(Float::isFinite))
                                actual[index].indices.maxOf { abs(actual[index][it]-expected[index][it]) }
                            }
                            assertTrue("Native input output mismatch: $errors",errors[0]<.05f && errors[1]<.01f)
                            val oldTimes=mutableListOf<Double>(); val newTimes=mutableListOf<Double>()
                            repeat(10) { iteration ->
                                fun old() { val tick=System.nanoTime();graph.prepare(source,0);PrivacyNativePixels.bitmapToTensor(graph.modelBitmap,floats);floats.asFloatBuffer().get(input);inputs.single().writeFloat(input);reference.run(inputs,outputs);outputs.forEach {it.readFloat()};oldTimes+=(System.nanoTime()-tick)/1e6 }
                                fun next() { val tick=System.nanoTime();graph.prepare(source,0,readModel=false);graph.predictNativeInput();newTimes+=(System.nanoTime()-tick)/1e6 }
                                if(iteration%2==0) {old();next()} else {next();old()}
                            }
                            Log.i("PrivacyStages","stage=gpu_input pattern=$pattern bitmap_float_p50_ms=${oldTimes.sorted()[5]} gl_buffer_p50_ms=${newTimes.sorted()[5]} errors=$errors")
                        }
                    }
                } finally { (inputs+outputs).forEach { it.close() } }
            }
        } finally { source.release();file.delete() }
    }
}
