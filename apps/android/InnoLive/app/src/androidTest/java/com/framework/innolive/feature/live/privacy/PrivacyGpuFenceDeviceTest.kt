package com.framework.innolive.feature.live.privacy

import android.graphics.Matrix
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

@RunWith(AndroidJUnit4::class)
class PrivacyGpuFenceDeviceTest {
    private fun initialize() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
    }
    @Test fun compareBlockingFinishAndFencedHandoffWithIdenticalProtectedPixels() {
        initialize()
        val root=EglBase.create()
        val source=JavaI420Buffer.allocate(1920,1080)
        repeat(source.dataY.capacity()) { source.dataY.put(it,(16+it*13%219).toByte()) }
        repeat(source.dataU.capacity()) {source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte())}
        try {
            PrivacyGpuFramePipeline(root.eglBaseContext,useGles3=true,useFence=false).use { old ->
                PrivacyGpuFramePipeline(root.eglBaseContext,useGles3=true,useFence=true).use { next ->
                    val mask=ByteArray(160*160){-1}
                    val blocking=mutableListOf<Double>();val fenced=mutableListOf<Double>()
                    fun render(graph:PrivacyGpuFramePipeline,times:MutableList<Double>):ByteArray {
                        val tick=System.nanoTime()
                        val layout=graph.prepare(source,0,readModel=false)
                        val frame=graph.finish(mask,layout,1920,1080)
                        times+=(System.nanoTime()-tick)/1e6
                        val pixels=checkNotNull(frame.toI420())
                        try {
                            return ByteArray(1920*1080).also { output ->
                                for(y in 0 until 1080) for(x in 0 until 1920) output[y*1920+x]=pixels.dataY.get(y*pixels.strideY+x)
                            }
                        } finally {pixels.release();frame.release()}
                    }
                    repeat(4) {render(old,blocking);render(next,fenced)};blocking.clear();fenced.clear()
                    repeat(20) {
                        val first:ByteArray;val second:ByteArray
                        if(it%2==0) {first=render(old,blocking);second=render(next,fenced)}
                        else {second=render(next,fenced);first=render(old,blocking)}
                        assertArrayEquals(first,second)
                    }
                    assertTrue("Device did not support EGL fence",next.lastFenceUsed)
                    Log.i("PrivacyStages","stage=gpu_fence blocking_handoff_p50_ms=${blocking.sorted()[10]} fenced_handoff_p50_ms=${fenced.sorted()[10]} identical=true")
                }
            }
        } finally {source.release();root.release()}
    }
    @Test fun croppedAndTransformedViewsKeepFenceAndStorageAliveAfterGraphClose() {
        initialize()
        val source=JavaI420Buffer.allocate(64,64)
        repeat(source.dataY.capacity()) {source.dataY.put(it,90.toByte())}
        repeat(source.dataU.capacity()) {source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte())}
        val graph=PrivacyGpuFramePipeline(null,useGles3=true,useFence=true)
        val layout=graph.prepare(source,0,readModel=false)
        val original=graph.finish(ByteArray(160*160),layout,64,64)
        val transformed=original.applyTransformMatrix(Matrix(),64,64)
        val crop=transformed.cropAndScale(0,0,32,32,32,32) as VideoFrame.TextureBuffer
        original.release();transformed.release();graph.close()
        try {
            val pixels=checkNotNull(crop.toI420())
            try {assertTrue((pixels.dataY.get(0).toInt() and 255) in 85..95)} finally {pixels.release()}
        } finally {crop.release();source.release()}
    }
}
