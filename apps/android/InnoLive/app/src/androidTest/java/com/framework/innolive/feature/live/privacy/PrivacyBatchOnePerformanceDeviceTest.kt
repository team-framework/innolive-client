package com.framework.innolive.feature.live.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

/** Alternating A/B: real YOLO, identical pixels, fixed full protection, complete GPU consumption. */
@RunWith(AndroidJUnit4::class)
class PrivacyBatchOnePerformanceDeviceTest {
    @Test fun compareBundleWithFixedPixelsAndProtectionWorkload() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        for ((width,height) in listOf(1920 to 1080,1280 to 720)) {
            val source=JavaI420Buffer.allocate(width,height)
            repeat(source.dataY.capacity()) {source.dataY.put(it,(32+it%width%180).toByte())}
            repeat(source.dataU.capacity()) {source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte())}
            val full=ByteArray(160*160) {-1}
            try {
                PrivacyGpuFramePipeline(null,useGles3=true,cacheBindings=false).use {oldGraph ->
                    PrivacyGpuFramePipeline(null,useGles3=true,cacheBindings=true).use {newGraph ->
                        PrivacyOnnxModel(context,nativePostprocessing=false,batchTwoOptimizations=false).use {old ->
                            PrivacyOnnxModel(context,nativePostprocessing=true,batchTwoOptimizations=false).use {new ->
                                val before=ArrayList<Double>();val after=ArrayList<Double>()
                                val oldSubmit=ArrayList<Double>();val newSubmit=ArrayList<Double>()
                                fun run(graph:PrivacyGpuFramePipeline,model:PrivacyOnnxModel,index:Int,optimized:Boolean):VideoFrame.I420Buffer {
                                    val start=System.nanoTime()
                                    val layout=graph.prepare(source,90,readModel=!graph.nativeInputEnabled)
                                    val texture=model.processPrepared(graph.modelBitmap,layout,1_000_000_000L+index*33_000_000L,
                                        {_,_->emptySet()},renderOnGpu=true,gpuGraph=graph) {
                                        graph.finish(full,layout,width,height,full.size)
                                    }
                                    val submit=(System.nanoTime()-start)/1e6
                                    val output=try {checkNotNull(texture.toI420())} finally {texture.release()}
                                    if(index>=5) {
                                        (if(optimized)after else before).add((System.nanoTime()-start)/1e6)
                                        (if(optimized)newSubmit else oldSubmit).add(submit)
                                    }
                                    assertTrue(graph.nativeInputEnabled)
                                    assertTrue(model.usesGpu)
                                    return output
                                }
                                repeat(45) {index ->
                                    val a:VideoFrame.I420Buffer;val b:VideoFrame.I420Buffer
                                    if(index%2==0) {a=run(oldGraph,old,index,false);b=run(newGraph,new,index,true)}
                                    else {b=run(newGraph,new,index,true);a=run(oldGraph,old,index,false)}
                                    try {
                                        if(index==0 || index==44) {
                                            for((left,right) in listOf(a.dataY to b.dataY,a.dataU to b.dataU,a.dataV to b.dataV)) {
                                                val aa=ByteArray(left.remaining()).also {left.duplicate().get(it)}
                                                val bb=ByteArray(right.remaining()).also {right.duplicate().get(it)}
                                                assertArrayEquals(aa,bb)
                                            }
                                        }
                                    } finally {a.release();b.release()}
                                }
                                fun percentile(values:List<Double>,fraction:Double)=values.sorted()[(values.size*fraction).toInt().coerceAtMost(values.lastIndex)]
                                Log.i("PrivacyBatch","batch=1 stage=fixed_frame size=${width}x$height samples=${before.size} " +
                                    "baseline_p50_ms=${percentile(before,.5)} optimized_p50_ms=${percentile(after,.5)} " +
                                    "baseline_p95_ms=${percentile(before,.95)} optimized_p95_ms=${percentile(after,.95)} " +
                                    "baseline_submit_p50_ms=${percentile(oldSubmit,.5)} optimized_submit_p50_ms=${percentile(newSubmit,.5)} " +
                                    "protected=true consumer=i420 pixels_identical=true")
                            }
                        }
                    }
                }
            } finally {source.release()}
        }
    }
}
