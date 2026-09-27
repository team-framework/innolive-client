package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PrivacyFaceReadbackDeviceTest {
    @Test fun discardedSampleRestoresVideoContextAndAllowsNextSample() {
        initialize()
        val source=fixture()
        try {
            PrivacyGpuFramePipeline(null,useGles3=true).use { graph ->
                graph.runFrame {
                    val layout=graph.prepare(source,0,readModel=false)
                    val sample=graph.cropAsync(Rect(20,40,116,136))
                    sample.close()
                    val frame=graph.finish(ByteArray(160*160) {-1},layout,1920,1080)
                    try {checkNotNull(frame.toI420()).release()} finally {frame.release()}
                    graph.cropAsync(Rect(20,40,116,136)).close()
                }
            }
        } finally {source.release()}
    }
    @Test fun deferredCropPreservesPixelsAfterProducerChangesAndCloses() {
        initialize()
        val source = fixture()
        val worker = Executors.newSingleThreadExecutor()
        val graph = PrivacyGpuFramePipeline(null,useGles3=true)
        try {
            graph.prepare(source,0,readModel=false)
            val bounds = Rect(71,83,721,583)
            val expected = graph.crop(bounds).usePixels()
            val sample = graph.cropAsync(bounds)
            assertThrows(IllegalStateException::class.java) { graph.cropAsync(bounds) }
            repeat(source.dataY.capacity()) { source.dataY.put(it,16) }
            graph.prepare(source,0,readModel=false)
            graph.close()
            val actual = worker.submit<IntArray> { sample.read().usePixels() }.get(5,TimeUnit.SECONDS)
            assertArrayEquals(expected,actual)
            sample.close();sample.close()
            assertThrows(IllegalStateException::class.java) { sample.read() }
        } finally {graph.close();source.release();worker.shutdown()}
    }

    @Test fun compareSynchronousAndDeferredFullResolutionCrops() {
        initialize()
        val source = fixture()
        val worker = Executors.newSingleThreadExecutor()
        try {
            PrivacyGpuFramePipeline(null,useGles3=true).use { graph ->
                for(bounds in listOf(Rect(0,0,1920,1080),Rect(500,200,1000,850),Rect(20,40,116,136))) {
                    val baseline = mutableListOf<Double>();val submit = mutableListOf<Double>();val total=mutableListOf<Double>()
                    repeat(28) { index ->
                        fun synchronous():IntArray {
                            graph.prepare(source,0,readModel=false)
                            val tick=System.nanoTime()
                            val image=graph.crop(bounds)
                            if(index>=4)baseline.add((System.nanoTime()-tick)/1e6)
                            return image.usePixels()
                        }
                        fun deferred():IntArray {
                            graph.prepare(source,0,readModel=false)
                            val tick=System.nanoTime()
                            val sample=graph.cropAsync(bounds)
                            val issueMs=(System.nanoTime()-tick)/1e6
                            val image=worker.submit<Bitmap> {sample.read()}.get(5,TimeUnit.SECONDS)
                            if(index>=4) {submit.add(issueMs);total.add((System.nanoTime()-tick)/1e6)}
                            return image.usePixels()
                        }
                        val expected:IntArray;val actual:IntArray
                        if(index%2==0) {expected=synchronous();actual=deferred()}
                        else {actual=deferred();expected=synchronous()}
                        assertArrayEquals("Deferred crop must preserve original resolution pixels",expected,actual)
                    }
                    fun p(values:List<Double>,q:Double)=values.sorted()[(values.size*q).toInt()]
                    Log.i("PrivacyStages","stage=face_roi_readback size=${bounds.width()}x${bounds.height()} samples=24 " +
                        "baseline_p50_ms=${p(baseline,.5)} submit_p50_ms=${p(submit,.5)} total_p50_ms=${p(total,.5)} " +
                        "baseline_p95_ms=${p(baseline,.95)} submit_p95_ms=${p(submit,.95)} total_p95_ms=${p(total,.95)} parity_pass=true")
                }
            }
        } finally {source.release();worker.shutdown()}
    }

    private fun initialize() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
    }
    private fun fixture()=JavaI420Buffer.allocate(1920,1080).also { source ->
        repeat(source.dataY.capacity()) {source.dataY.put(it,(16+(it%1920*7+it/1920*13)%219).toByte())}
        repeat(source.dataU.capacity()) {source.dataU.put(it,120.toByte());source.dataV.put(it,145.toByte())}
    }
    private fun Bitmap.usePixels():IntArray = try {
        IntArray(width*height).also {getPixels(it,0,width,0,0,width,height)}
    } finally {recycle()}
}
