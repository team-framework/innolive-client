package com.framework.innolive.feature.live.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PrivacyFaceVariantDeviceTest {
    @Test fun compareGatherLookupAgainstPinnedOneHotGraph() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val candidateFile=File(context.getExternalFilesDir(null),"privacy-face-gather.tflite")
        assumeTrue("Optional separately exported diagnostic model was not supplied",candidateFile.isFile)
        fun options()=CompiledModel.Options(Accelerator.GPU,Accelerator.CPU).apply {
            gpuOptions=CompiledModel.GpuOptions(precision=CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM)
            cpuOptions=CompiledModel.CpuOptions(numThreads=4)
        }
        val oldTimes=mutableListOf<Double>();val newTimes=mutableListOf<Double>()
        CompiledModel.create(context.assets,"privacy-face.tflite",options()).use { old ->
            CompiledModel.create(candidateFile.absolutePath,options()).use { candidate ->
                val oldInput=old.createInputBuffers();val newInput=candidate.createInputBuffers()
                val oldOutput=old.createOutputBuffers();val newOutput=candidate.createOutputBuffers()
                try {
                    repeat(18) { index ->
                        val image=FloatArray(3*112*112) {((it*31L+index*17)%255)/127.5f-1f}
                        val points=floatArrayOf(.34f,.46f,.66f,.46f,.50f,.64f,.37f,.82f,.63f,.82f)
                        fun run(model:CompiledModel,inputs:List<com.google.ai.edge.litert.TensorBuffer>,
                            outputs:List<com.google.ai.edge.litert.TensorBuffer>,times:MutableList<Double>):FloatArray {
                            inputs[0].writeFloat(image);inputs[1].writeFloat(points)
                            val tick=System.nanoTime();model.run(inputs,outputs)
                            val result=checkNotNull(PrivacyFaceMath.normalize(outputs.single().readFloat()))
                            if(index>=4)times.add((System.nanoTime()-tick)/1e6)
                            return result
                        }
                        val wanted:FloatArray;val actual:FloatArray
                        if(index%2==0) {wanted=run(old,oldInput,oldOutput,oldTimes);actual=run(candidate,newInput,newOutput,newTimes)}
                        else {actual=run(candidate,newInput,newOutput,newTimes);wanted=run(old,oldInput,oldOutput,oldTimes)}
                        assertTrue("Gather embedding mismatch",PrivacyFaceMath.cosine(wanted,actual)>=.999f &&
                            wanted.indices.maxOf {abs(wanted[it]-actual[it])}<.01f)
                    }
                    fun percentile(values:List<Double>,p:Double)=values.sorted()[(values.size*p).toInt()]
                    Log.i("PrivacyStages","stage=face_variant variant=gather samples=14 " +
                        "baseline_p50_ms=${percentile(oldTimes,.5)} candidate_p50_ms=${percentile(newTimes,.5)} " +
                        "baseline_p95_ms=${percentile(oldTimes,.95)} candidate_p95_ms=${percentile(newTimes,.95)} parity_pass=true")
                } finally {(oldInput+newInput+oldOutput+newOutput).forEach {it.close()}}
            }
        }
    }
}
