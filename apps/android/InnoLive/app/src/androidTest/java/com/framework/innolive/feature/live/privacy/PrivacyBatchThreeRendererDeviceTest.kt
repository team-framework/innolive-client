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
import kotlin.math.abs

/** Same input and mask, alternating renderer A/B including the I420 consumer fence. */
@RunWith(AndroidJUnit4::class)
class PrivacyBatchThreeRendererDeviceTest {
    @Test fun isolateSparseMaskPixelDifferencesByComponent() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val source=JavaI420Buffer.allocate(1920,1080)
        try {
            for(y in 0 until 1080) for(x in 0 until 1920)
                source.dataY.put(y*source.strideY+x,(16+(x*7+y*13)%220).toByte())
            repeat(source.dataU.capacity()) {source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte())}
            val mask=ByteArray(160*160)
            for(y in 50..70) for(x in 70..90)mask[y*160+x]=(-1).toByte()
            fun render(graph:PrivacyGpuFramePipeline):VideoFrame.I420Buffer {
                val layout=graph.prepare(source,90,readModel=false)
                val texture=graph.finish(mask,layout,1920,1080,mask.count {it.toInt()!=0})
                return try {checkNotNull(texture.toI420())} finally {texture.release()}
            }
            PrivacyGpuFramePipeline(null,useGles3=true,optimizedRenderer=false).use {old ->
                val reference=render(old)
                try {
                    for(mode in listOf("r8","fused","paired","roi","all")) {
                        PrivacyGpuFramePipeline(null,useGles3=true,compactIntermediates=mode=="r8"||mode=="all",
                            fusedComposite=mode=="fused"||mode=="all",pairedKernels=mode=="paired"||mode=="all",
                            roiBlur=mode=="roi").use {graph ->
                            val candidate=render(graph)
                            try {
                                var maximum=0;var changed=0
                                for((a,b) in listOf(reference.dataY to candidate.dataY,reference.dataU to candidate.dataU,reference.dataV to candidate.dataV))
                                    for(i in 0 until a.remaining()) {
                                        val delta=abs((a.get(i).toInt() and 255)-(b.get(i).toInt() and 255))
                                        maximum=maxOf(maximum,delta);if(delta>0)changed++
                                    }
                                Log.i("PrivacyBatch","batch=3 stage=isolate mode=$mode max_delta=$maximum changed=$changed")
                            } finally {candidate.release()}
                        }
                    }
                } finally {reference.release()}
            }
        } finally {source.release()}
    }

    @Test fun masksRemainProtectedAndRendererGetsFaster() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                val isolation=InstrumentationRegistry.getArguments().getString("privacyRendererIsolation", "all")
        val sizes=if(isolation=="all"||isolation=="roi")listOf(1920 to 1080,1280 to 720) else listOf(1280 to 720)
        for((width,height) in sizes) {
            val source=JavaI420Buffer.allocate(width,height)
            try {
                for(y in 0 until height) for(x in 0 until width)
                    source.dataY.put(y*source.strideY+x,(16+(x*7+y*13)%220).toByte())
                repeat(source.dataU.capacity()) {source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte())}
                PrivacyGpuFramePipeline(null,useGles3=true,optimizedRenderer=isolation=="roi",
                    roiBlur=false).use {old ->
                    PrivacyGpuFramePipeline(null,useGles3=true,
                        compactIntermediates=isolation=="all" || isolation=="r8" || isolation=="roi",
                        pairedKernels=isolation=="all" || isolation=="paired" || isolation=="roi",
                        fusedComposite=isolation=="all" || isolation=="fused" || isolation=="roi",
                        roiBlur=isolation=="roi").use {next ->
                        for(maskType in if(isolation=="all"||isolation=="roi")listOf("full","sparse","edge","empty") else listOf("sparse")) {
                            val mask=ByteArray(160*160)
                            when(maskType) {
                                "full" -> mask.fill(-1)
                                "sparse" -> for(y in 50..70) for(x in 70..90) mask[y*160+x]=(-1).toByte()
                                "edge" -> for(y in 0..14) for(x in 0..21) mask[y*160+x]=(-1).toByte()
                            }
                            val baseline=ArrayList<Double>();val optimized=ArrayList<Double>()
                            fun render(graph:PrivacyGpuFramePipeline,values:MutableList<Double>,sample:Int):VideoFrame.I420Buffer {
                                val start=System.nanoTime()
                                val layout=graph.prepare(source,90,readModel=false)
                                val texture=graph.finish(mask,layout,width,height,mask.count {it.toInt()!=0})
                                val output=try {checkNotNull(texture.toI420())} finally {texture.release()}
                                if(sample>=5) values.add((System.nanoTime()-start)/1e6)
                                return output
                            }
                            repeat(if(isolation=="all"||isolation=="roi")35 else 8) {sample ->
                                val a:VideoFrame.I420Buffer;val b:VideoFrame.I420Buffer
                                if(sample%2==0) {a=render(old,baseline,sample);b=render(next,optimized,sample)}
                                else {b=render(next,optimized,sample);a=render(old,baseline,sample)}
                                try {
                                    if(sample==0 || sample==34 || sample==7) {
                                        var maxDelta=0;var changed=0
                                        for((left,right) in listOf(a.dataY to b.dataY,a.dataU to b.dataU,a.dataV to b.dataV)) {
                                            for(i in 0 until left.remaining()) {
                                                val delta=abs((left.get(i).toInt() and 255)-(right.get(i).toInt() and 255))
                                                maxDelta=maxOf(maxDelta,delta)
                                                if(delta>0)changed++
                                            }
                                        }
                                        Log.i("PrivacyBatch","batch=3 isolation=$isolation stage=parity size=${width}x$height mask=$maskType max_delta=$maxDelta changed=$changed")
                                        assertTrue("Privacy renderer changed too many pixel levels: $maxDelta",maxDelta<=3)
                                    }
                                } finally {a.release();b.release()}
                            }
                            fun percentile(v:List<Double>,q:Double)=v.sorted()[(v.size*q).toInt().coerceAtMost(v.lastIndex)]
                            Log.i("PrivacyBatch","batch=3 isolation=$isolation stage=renderer size=${width}x$height mask=$maskType samples=${baseline.size} baseline_p50_ms=${percentile(baseline,.5)} optimized_p50_ms=${percentile(optimized,.5)} baseline_p95_ms=${percentile(baseline,.95)} optimized_p95_ms=${percentile(optimized,.95)}")
                        }
                    }
                }
            } finally {source.release()}
        }
    }
}
