package com.framework.innolive.feature.live

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.privacy.PrivacyNativePixels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

@RunWith(AndroidJUnit4::class)
class PrivacyCameraBufferPoolDeviceTest {
    private fun initialize() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
    }
    @Test fun heldCropIsNeverReusedAndCanOutlivePoolClose() {
        initialize()
        val pool=PrivacyCameraBufferPool()
        val first=pool.acquire(64,64)
        first.dataY.put(0,42)
        val crop=first.cropAndScale(0,0,64,64,64,64)
        val held=crop.toI420()!!
        crop.release()
        first.release()
        val next=pool.acquire(64,64)
        next.dataY.put(0,99)
        assertEquals(42.toByte(),held.dataY.get(0))
        next.release()
        val resized=pool.acquire(32,32)
        assertEquals(32,resized.width)
        resized.release()
        pool.close()
        assertEquals(42.toByte(),held.dataY.get(0))
        held.release()
        assertThrows(IllegalStateException::class.java) { pool.acquire(64,64) }
    }
    @Test fun compareAllocationWithReuseIncludingAllCameraPlaneCopies() {
        initialize()
        for((width,height) in listOf(1920 to 1080,1280 to 720)) {
            val input=JavaI420Buffer.allocate(width,height)
            repeat(input.dataY.capacity()) { input.dataY.put(it,(it%219+16).toByte()) }
            repeat(input.dataU.capacity()) {input.dataU.put(it,110);input.dataV.put(it,140.toByte())}
            PrivacyCameraBufferPool().use { pool ->
                val old=mutableListOf<Double>();val next=mutableListOf<Double>()
                fun run(reuse:Boolean) {
                    val tick=System.nanoTime()
                    val output:VideoFrame.I420Buffer=if(reuse) pool.acquire(width,height) else JavaI420Buffer.allocate(width,height)
                    PrivacyNativePixels.copyPlane(input.dataY,input.strideY,1,width,height,output.dataY,output.strideY)
                    PrivacyNativePixels.copyPlane(input.dataU,input.strideU,1,width/2,height/2,output.dataU,output.strideU)
                    PrivacyNativePixels.copyPlane(input.dataV,input.strideV,1,width/2,height/2,output.dataV,output.strideV)
                    output.release()
                    (if(reuse) next else old)+=(System.nanoTime()-tick)/1e6
                }
                repeat(5) { run(false);run(true) };old.clear();next.clear()
                repeat(40) { if(it%2==0) {run(false);run(true)} else {run(true);run(false)} }
                val output=pool.acquire(width,height)
                assertEquals(input.dataY.get(width*height-1),output.dataY.get(width*height-1))
                assertEquals(input.dataU.get(0),output.dataU.get(0));assertEquals(input.dataV.get(0),output.dataV.get(0))
                output.release()
                Log.i("PrivacyStages","stage=camera_pool width=$width allocated_p50_ms=${old.sorted()[20]} reused_p50_ms=${next.sorted()[20]}")
            }
            input.release()
        }
    }
}
