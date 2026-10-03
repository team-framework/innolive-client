package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.os.Process
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

/** Fixed detector/face pixels and 250ms recognition requests, with actual simultaneous GPU work. */
@RunWith(AndroidJUnit4::class)
class PrivacyBatchTwoContentionDeviceTest {
    @Test fun compareBundleWithFaceWorkerGpuContention() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val isolatePriority=InstrumentationRegistry.getArguments().getString("privacyIsolation", "")=="priority"
        val isolateRoi=InstrumentationRegistry.getArguments().getString("privacyIsolation", "")=="roi"
        val isolate=isolatePriority || isolateRoi
        class FaceWorker(val optimized:Boolean) : AutoCloseable {
            val executor=Executors.newSingleThreadExecutor()
            var model:PrivacyFaceModel?=null
            val pending=AtomicBoolean(false)
            val times=ConcurrentLinkedQueue<Double>()
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            var lastEmbedding:FloatArray?=null
            val image=Bitmap.createBitmap(PrivacyFaceGpuEngine.syntheticPixels(1),112,112,Bitmap.Config.ARGB_8888)
            val landmarks=floatArrayOf(.34f,.46f,.66f,.46f,.50f,.64f,.37f,.82f,.63f,.82f)
            init {
                try { executor.submit {
                    Process.setThreadPriority(if(optimized || isolateRoi) Process.THREAD_PRIORITY_MORE_FAVORABLE else Process.THREAD_PRIORITY_DEFAULT)
                    model=PrivacyFaceModel(context,reuseInputs=optimized || isolate)
                    assertTrue("Face GPU candidate must pass validation", checkNotNull(model).usesGpu)
                }.get(30,TimeUnit.SECONDS) } catch (error: Throwable) {
                    try { executor.submit { model?.close(); image.recycle() }.get(30,TimeUnit.SECONDS) }
                    finally { executor.shutdown() }
                    throw error
                }
            }
            fun request(ownedImage:Bitmap?=null,sample:PrivacyFaceReadback?=null,requestedAtNs:Long=System.nanoTime()) {
                if(!pending.compareAndSet(false,true)) {ownedImage?.recycle();sample?.close();return}
                executor.execute {
                    var snapshot:Bitmap?=ownedImage
                    var scaled:Bitmap?=null
                    try {
                        val start=if(isolateRoi)requestedAtNs else System.nanoTime()
                        if(sample!=null)snapshot=sample.read()
                        val input=snapshot?.let {
                            Bitmap.createScaledBitmap(it,112,112,true).also { resized -> scaled=resized;resized.setHasAlpha(false) }
                        } ?: image
                        lastEmbedding=checkNotNull(model).predict(input,landmarks)
                        times.add((System.nanoTime()-start)/1e6)
                    } catch(error:Throwable) {failure.compareAndSet(null,error)} finally {
                        try {
                            if(scaled!==snapshot)scaled?.recycle()
                            snapshot?.recycle();sample?.close()
                        } finally {pending.set(false)}
                    }
                }
            }
            fun drain() {executor.submit {}.get(5,TimeUnit.SECONDS); failure.get()?.let {throw AssertionError("Face worker failed",it)}}
            override fun close() {
                try {executor.submit {model?.close();model=null;image.recycle()}.get(10,TimeUnit.SECONDS)}
                finally {executor.shutdown()}
            }
        }
        FaceWorker(false).use {oldFace -> FaceWorker(true).use {newFace ->
            val source=JavaI420Buffer.allocate(1280,720)
            try {
                repeat(source.dataY.capacity()) {source.dataY.put(it,(32+it%1280%180).toByte())}
                repeat(source.dataU.capacity()) {source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte())}
                PrivacyGpuFramePipeline(null,useGles3=true).use {oldGraph ->
                    PrivacyGpuFramePipeline(null,useGles3=true).use {newGraph ->
                        PrivacyOnnxModel(context,batchTwoOptimizations=isolate).use {old ->
                            PrivacyOnnxModel(context,batchTwoOptimizations=true).use {new ->
                                val full=ByteArray(160*160) {-1}
                                fun run(optimized:Boolean,face:FaceWorker,request:Boolean):Double {
                                    val graph=if(optimized)newGraph else oldGraph
                                    val model=if(optimized)new else old
                                    val start=System.nanoTime()
                                    if(request && !optimized && !isolate) face.request()
                                    fun submit():org.webrtc.VideoFrame.TextureBuffer {
                                        val layout=graph.prepare(source,90,readModel=!graph.nativeInputEnabled)
                                        return model.processPrepared(graph.modelBitmap,layout,System.nanoTime(),{_,_->
                                            if(request && (optimized || isolate) && !face.pending.get()) {
                                                if(isolateRoi) {
                                                    val tick=System.nanoTime();val bounds=android.graphics.Rect(100,100,600,750)
                                                    if(optimized)face.request(sample=graph.cropAsync(bounds),requestedAtNs=tick)
                                                    else face.request(ownedImage=graph.crop(bounds),requestedAtNs=tick)
                                                } else face.request()
                                            }
                                            emptySet()
                                        },renderOnGpu=true,gpuGraph=graph) {graph.finish(full,layout,1280,720,full.size)}
                                    }
                                    val texture=if(optimized || isolate)graph.runFrame {submit()} else submit()
                                    try {checkNotNull(texture.toI420()).release()} finally {texture.release()}
                                    return (System.nanoTime()-start)/1e6
                                }
                                run(false,oldFace,false);run(true,newFace,false)
                                val baseline=ArrayList<Double>();val optimized=ArrayList<Double>()
                                for((phase,enabled) in listOf(false,true,true,false).withIndex()) {
                                    val face=if(enabled)newFace else oldFace
                                    var nextFace=0L
                                    val until=System.nanoTime()+8_000_000_000L
                                    var frames=0
                                    while(System.nanoTime()<until) {
                                        val start=System.nanoTime()
                                        val request=start>=nextFace
                                        if(request) nextFace=start+250_000_000L
                                        val ms=run(enabled,face,request)
                                        (if(enabled)optimized else baseline).add(ms)
                                        frames++
                                        Thread.sleep(maxOf(1L,33-(System.nanoTime()-start)/1_000_000L))
                                    }
                                    face.drain()
                                    Log.i("PrivacyBatch","batch=2 isolation=$isolatePriority stage=contention_phase phase=$phase optimized=$enabled frames=$frames")
                                }
                                fun p(values:List<Double>,q:Double)=values.sorted()[(values.size*q).toInt().coerceAtMost(values.lastIndex)]
                                assertTrue(oldFace.times.size>20 && newFace.times.size>20)
                                if(isolateRoi)assertTrue("Deferred ROI embedding parity",PrivacyFaceMath.cosine(checkNotNull(oldFace.lastEmbedding),checkNotNull(newFace.lastEmbedding))>=.999f)
                                Log.i("PrivacyBatch","batch=2 isolation=$isolatePriority roi_isolation=$isolateRoi stage=contention size=1280x720 samples=${baseline.size}/${optimized.size} baseline_p50_ms=${p(baseline,.5)} optimized_p50_ms=${p(optimized,.5)} baseline_p95_ms=${p(baseline,.95)} optimized_p95_ms=${p(optimized,.95)} baseline_face_p50_ms=${p(oldFace.times.toList(),.5)} optimized_face_p50_ms=${p(newFace.times.toList(),.5)} baseline_face_p95_ms=${p(oldFace.times.toList(),.95)} optimized_face_p95_ms=${p(newFace.times.toList(),.95)} face_requests=250ms synthetic=true")
                            }
                        }
                    }
                }
            } finally {source.release()}
        } }
    }
}
