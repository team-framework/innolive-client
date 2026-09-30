package com.framework.innolive.feature.live.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import java.io.File
import kotlin.math.abs

/** Experimental runtime buffers/layouts use the same weights; product SHA and selection stay pinned. */
@RunWith(AndroidJUnit4::class)
class PrivacyDetectorVariantDeviceTest {
    @Test fun compareGlOutputsAgainstManagedOutputs() = compare("gl_output",null,true)
    @Test fun compareNhwcExportAgainstPinnedNchw() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"privacy-detector-nhwc.tflite")
        assumeTrue("Optional separately exported diagnostic model was not supplied",file.isFile)
        compare("nhwc",file,false)
    }
    private fun compare(label:String,file:File?,glOutputs:Boolean) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val source=JavaI420Buffer.allocate(640,480)
        val baselineTimes=mutableListOf<Double>();val candidateTimes=mutableListOf<Double>()
        try {
            PrivacyGpuFramePipeline(null,useGles3=true).use { baseline ->
                PrivacyGpuFramePipeline(null,useGles3=true).use { candidate ->
                    baseline.createNativeInputModel(PrivacyDetectorGpuEngine.verifiedFile(context).absolutePath)
                    candidate.createNativeInputModel((file ?: PrivacyDetectorGpuEngine.verifiedFile(context)).absolutePath,glOutputs)
                    repeat(28) { index ->
                        for(y in 0 until 480) for(x in 0 until 640)
                            source.dataY.put(y*source.strideY+x,(16+(x*13+y*7+index*31)%220).toByte())
                        repeat(source.dataU.capacity()) {source.dataU.put(it,(80+index%30).toByte())}
                        repeat(source.dataV.capacity()) {source.dataV.put(it,(160-index%30).toByte())}
                        fun run(graph:PrivacyGpuFramePipeline,times:MutableList<Double>):Pair<FloatArray,FloatArray> {
                            val tick=System.nanoTime();graph.prepare(source,0,readModel=false)
                            val result=graph.predictNativeInput()
                            if(index>=4)times.add((System.nanoTime()-tick)/1e6)
                            return result
                        }
                        val wanted:Pair<FloatArray,FloatArray>;val actual:Pair<FloatArray,FloatArray>
                        if(index%2==0) {wanted=run(baseline,baselineTimes);actual=run(candidate,candidateTimes)}
                        else {actual=run(candidate,candidateTimes);wanted=run(baseline,baselineTimes)}
                        val first=actual.first.indices.maxOf {abs(actual.first[it]-wanted.first[it])}
                        val second=actual.second.indices.maxOf {abs(actual.second[it]-wanted.second[it])}
                        assertTrue("Variant output differs $label: $first/$second",first<.05f && second<.01f)
                    }
                    fun percentile(values:List<Double>,p:Double)=values.sorted()[(values.size*p).toInt()]
                    Log.i("PrivacyStages","stage=detector_variant variant=$label samples=24 " +
                        "baseline_p50_ms=${percentile(baselineTimes,.5)} candidate_p50_ms=${percentile(candidateTimes,.5)} " +
                        "baseline_p95_ms=${percentile(baselineTimes,.95)} candidate_p95_ms=${percentile(candidateTimes,.95)} parity_pass=true")
                }
            }
        } finally {source.release()}
    }
}
