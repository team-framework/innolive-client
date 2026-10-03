package com.framework.innolive.feature.live.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory

@RunWith(AndroidJUnit4::class)
class PrivacyCompactMaskDeviceTest {
    @Test fun singleChannelMaskMatchesRgbaForOpaqueAndFadedProtectionAndReducesPackingTime() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val source=JavaI420Buffer.allocate(640,480)
        repeat(source.dataY.capacity()) {source.dataY.put(it,(16+it*13%219).toByte())}
        repeat(source.dataU.capacity()) {source.dataU.put(it,110.toByte());source.dataV.put(it,150.toByte())}
        try {
            PrivacyGpuFramePipeline(null,useGles3=true,compactMask=false).use { old ->
                PrivacyGpuFramePipeline(null,useGles3=true,compactMask=true).use { next ->
                    for(pattern in 0..1) {
                        val mask=ByteArray(160*160) {index -> if(pattern==0) (-1).toByte() else ((index*29)%256).toByte()}
                        val rgba=mutableListOf<Double>();val compact=mutableListOf<Double>()
                        fun render(graph:PrivacyGpuFramePipeline,times:MutableList<Double>):ByteArray {
                            val layout=graph.prepare(source,90,readModel=false)
                            val tick=System.nanoTime();val frame=graph.finish(mask,layout,640,480)
                            times+=(System.nanoTime()-tick)/1e6
                            val pixels=checkNotNull(frame.toI420())
                            try {
                                return ByteArray(640*480+2*320*240).also {bytes ->
                                    for(y in 0 until 480) for(x in 0 until 640) bytes[y*640+x]=pixels.dataY.get(y*pixels.strideY+x)
                                    for(y in 0 until 240) for(x in 0 until 320) {
                                        bytes[640*480+y*320+x]=pixels.dataU.get(y*pixels.strideU+x)
                                        bytes[640*480+320*240+y*320+x]=pixels.dataV.get(y*pixels.strideV+x)
                                    }
                                }
                            } finally {pixels.release();frame.release()}
                        }
                        repeat(5) {render(old,rgba);render(next,compact)};rgba.clear();compact.clear()
                        repeat(20) {sample ->
                            val first:ByteArray;val second:ByteArray
                            if(sample%2==0) {first=render(old,rgba);second=render(next,compact)}
                            else {second=render(next,compact);first=render(old,rgba)}
                            assertArrayEquals(first,second)
                        }
                        Log.i("PrivacyStages","stage=mask_upload pattern=$pattern rgba_render_p50_ms=${rgba.sorted()[10]} compact_render_p50_ms=${compact.sorted()[10]} identical=true")
                    }
                }
            }
        } finally {source.release()}
    }
}
