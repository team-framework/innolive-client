package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import com.framework.innolive.BuildConfig
import org.webrtc.VideoFrame
import org.webrtc.JavaI420Buffer
import org.webrtc.EglBase

/** Serial AI worker: a GPU image graph and protected texture output, with a protected CPU fallback. */
internal class PrivacyFrameProcessor(context: Context, private val sharedContext: EglBase.Context? = null,
                                     private val allowGpuImages: Boolean = true, directGpuInput:Boolean=true,
                                     private val nativePostprocessing: Boolean = true,
                                     private val batchTwoOptimizations: Boolean = true) : AutoCloseable {
    private var imageGpu: PrivacyGpuFramePipeline? = null
    private var imageGpuUnavailable = !allowGpuImages
    private val model = PrivacyOnnxModel(context.applicationContext,directGpuInput=directGpuInput,
        nativePostprocessing=nativePostprocessing,batchTwoOptimizations=batchTwoOptimizations)
    private val pixels = PrivacyPixelConverter()
    private val faces = PrivacyFaceCoordinator(context.applicationContext, optimized=nativePostprocessing)
    var lastTimings: PrivacyFrameTimings? = null
        private set
    val lastAnalysis: PrivacyFrameAnalysis? get() = model.lastAnalysis
    var lastCameraCopiedPlanes: Int = 3
        private set
    private var lastLogNs = System.nanoTime()
    private val sensorPixels = BitmapScratch()
    private val uprightPixels = BitmapScratch()
    private val restoredPixels = BitmapScratch()
    private var geometry: Triple<Int, Int, Int>? = null

    /** Compile and validate the protected path before accepting real camera images. */
    fun prepare() {
        val neutral=JavaI420Buffer.allocate(640,360)
        try {
            for(i in 0 until neutral.dataY.capacity()) neutral.dataY.put(i,114.toByte())
            for(i in 0 until neutral.dataU.capacity()) {neutral.dataU.put(i,128.toByte()); neutral.dataV.put(i,128.toByte())}
            neutral.retain()
            val frame=VideoFrame(neutral,0,System.nanoTime())
            try {process(frame).release()} finally {frame.release()}
            // Exercise the protected branch even when the neutral image has no detections.
            imageGpu?.let { graph ->
                val full=ByteArray(160*160) {-1}
                graph.runFrame {
                    val layout=graph.prepare(neutral,0,readModel=false)
                    val output=graph.finish(full,layout,640,360,full.size)
                    try {output.toI420()?.release()} finally {output.release()}
                }
            }
            resetFaceExceptions();geometry=null
        } finally {neutral.release()}
    }

    fun resetFaceExceptions() { faces.reset(); model.resetTemporalState() }

    fun process(frame: VideoFrame): VideoFrame {
        lastCameraCopiedPlanes = 3
        if (!imageGpuUnavailable) {
            try {
                if (imageGpu == null) imageGpu = PrivacyGpuFramePipeline(sharedContext, useGles3 = true, cacheBindings=nativePostprocessing)
                val graph = checkNotNull(imageGpu)
                return if (batchTwoOptimizations) graph.runFrame { processGpu(frame, graph) } else processGpu(frame, graph)
            } catch (error: PrivacyGpuBackpressureException) { throw error }
            catch (error: Exception) {
                Log.w("PrivacyPipeline", "image_gpu_fallback type=${error.javaClass.simpleName}")
                releaseAccelerators(); imageGpu?.close(); imageGpu = null; imageGpuUnavailable = true
                resetFaceExceptions()
            }
        }
        return processCpu(frame)
    }

    fun process(camera: PrivacyCameraInput): VideoFrame {
        if(!imageGpuUnavailable) try {
            if(imageGpu==null) imageGpu=PrivacyGpuFramePipeline(sharedContext,useGles3=true,cacheBindings=nativePostprocessing)
            val graph=checkNotNull(imageGpu)
            fun processInput(): VideoFrame {
                val started=System.nanoTime()
                val layout=graph.prepare(camera,readModel=!graph.nativeInputEnabled)
                lastCameraCopiedPlanes=graph.lastCameraCopiedPlanes
                // Upload has captured the pixels; never borrow the camera during inference.
                camera.close()
                return processGpuPrepared(graph,layout,camera.width,camera.height,camera.rotation,
                    camera.timestampNs,started,System.nanoTime())
            }
            return if (batchTwoOptimizations) graph.runFrame { processInput() } else processInput()

        } catch(error:PrivacyGpuBackpressureException) {throw error}
        catch(error:Exception) {
            Log.w("PrivacyPipeline","camera_gpu_fallback type=${error.javaClass.simpleName}")
            val upright=try {
                if(camera.isClosed) checkNotNull(imageGpu).copyUpright() else null
            } finally {
                releaseAccelerators();imageGpu?.close();imageGpu=null;imageGpuUnavailable=true;resetFaceExceptions()
            }
            if(upright!=null) try {
                lastCameraCopiedPlanes=3
                return processCpuUpright(upright,camera.width,camera.height,camera.rotation,camera.timestampNs)
            } finally {upright.recycle()}
        }
        lastCameraCopiedPlanes=3
        val frame=VideoFrame(camera.copyI420(),camera.rotation,camera.timestampNs)
        camera.close()
        try {return processCpu(frame)} finally {frame.release()}
    }

    private fun processCpuUpright(upright:Bitmap,width:Int,height:Int,rotation:Int,timestampNs:Long):VideoFrame {
        val started=System.nanoTime()
        if (!batchTwoOptimizations) faces.beginFrame(upright,timestampNs)
        val protected=model.process(upright,timestampNs) {objects,layout ->
            if (batchTwoOptimizations) faces.currentFrame(upright,objects,layout,timestampNs)
            else faces.exceptions(upright,objects,layout,timestampNs)
        }
        val processed=System.nanoTime()
        try {
            val restored=rotate(protected,(360-rotation)%360,restoredPixels)
            check(restored.width==width && restored.height==height)
            val output=VideoFrame(pixels.toI420(restored),rotation,timestampNs)
            val completed=System.nanoTime()
            lastTimings=PrivacyFrameTimings(0.0,checkNotNull(model.lastTimings),
                (completed-processed)/1e6,(completed-started)/1e6)
            return output
        } finally {protected.recycle()}
    }

    private fun processGpu(frame: VideoFrame, graph: PrivacyGpuFramePipeline): VideoFrame {
        val started = System.nanoTime()
        val source = checkNotNull(frame.buffer.toI420())
        try {
            val layout = graph.prepare(source, frame.rotation, readModel = !graph.nativeInputEnabled)
            val converted = System.nanoTime()
            return processGpuPrepared(graph,layout,source.width,source.height,frame.rotation,
                frame.timestampNs,started,converted)
        } finally { source.release() }
    }

    private fun processGpuPrepared(graph:PrivacyGpuFramePipeline,layout:PrivacySegmentation.Letterbox,
        sensorWidth:Int,sensorHeight:Int,rotation:Int,timestampNs:Long,started:Long,converted:Long):VideoFrame {
            val nextGeometry=Triple(sensorWidth,sensorHeight,rotation)
            if(geometry!=nextGeometry) {resetFaceExceptions();geometry=nextGeometry}
            if (!batchTwoOptimizations) faces.beginFrame(layout.sourceWidth,layout.sourceHeight,timestampNs,graph::crop)
            val buffer=model.processPrepared(graph.modelBitmap,layout,timestampNs,
                {objects,box ->
                    if (batchTwoOptimizations) faces.currentFrame(box.sourceWidth,box.sourceHeight,objects,box,timestampNs,graph::crop)
                    else faces.exceptions(box.sourceWidth,box.sourceHeight,objects,box,timestampNs)
                },
                renderOnGpu=true,gpuGraph=graph) {mask -> graph.finish(mask,layout,sensorWidth,sensorHeight,model.lastMaskPixels)}
            val completed = System.nanoTime()
            lastTimings = PrivacyFrameTimings((converted - started) / 1e6,
                checkNotNull(model.lastTimings), 0.0, (completed - started) / 1e6)
            if (BuildConfig.DEBUG && completed - lastLogNs >= 5_000_000_000L) {
                lastLogNs = completed
                Log.i("PrivacyPipeline", "image_gpu=true texture_output=true size=${sensorWidth}x${sensorHeight} camera_copied_planes=$lastCameraCopiedPlanes $lastTimings")
            }
            return VideoFrame(buffer,rotation,timestampNs)
    }

    private fun processCpu(frame: VideoFrame): VideoFrame {
        val started = System.nanoTime()
        val source = checkNotNull(frame.buffer.toI420()) { "Camera frame could not be converted to I420" }
        try {
            val rotation = frame.rotation
            require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270)
            val nextGeometry = Triple(source.width, source.height, rotation)
            if (geometry != nextGeometry) { resetFaceExceptions(); geometry = nextGeometry }
            val sensor = sensorPixels.get(source.width, source.height)
            pixels.toBitmap(source, sensor)
            val upright = rotate(sensor, rotation, uprightPixels)
            val converted = System.nanoTime()
            if (!batchTwoOptimizations) faces.beginFrame(upright, frame.timestampNs)
            val protected = model.process(upright, frame.timestampNs) { objects, layout ->
                if (batchTwoOptimizations) faces.currentFrame(upright, objects, layout, frame.timestampNs)
                else faces.exceptions(upright, objects, layout, frame.timestampNs)
            }
            val processed = System.nanoTime()
            try {
                val restored = rotate(protected, (360 - rotation) % 360, restoredPixels)
                check(restored.width == frame.buffer.width && restored.height == frame.buffer.height)
                val output = VideoFrame(pixels.toI420(restored), rotation, frame.timestampNs)
                val completed = System.nanoTime()
                lastTimings = PrivacyFrameTimings((converted - started) / 1e6,
                    checkNotNull(model.lastTimings), (completed - processed) / 1e6,
                    (completed - started) / 1e6)
                if (BuildConfig.DEBUG && completed - lastLogNs >= 5_000_000_000L) {
                    lastLogNs = completed
                    Log.i("PrivacyPipeline", "size=${frame.buffer.width}x${frame.buffer.height} $lastTimings")
                }
                return output
            } finally {
                protected.recycle()
            }
        } finally {
            source.release()
        }
    }

    override fun close() {
        faces.close()
        try { val graph=imageGpu; if (graph != null && batchTwoOptimizations) graph.runFrame { model.close() } else model.close() } finally { imageGpu?.close(); imageGpu = null; sensorPixels.close(); uprightPixels.close(); restoredPixels.close() }
    }

    fun deactivateFaces() { faces.close() }

    private fun releaseAccelerators() {
        val graph=imageGpu
        if (batchTwoOptimizations && graph!=null) graph.runFrame { model.releaseAccelerators() }
        else model.releaseAccelerators()
    }

    private fun rotate(bitmap: Bitmap, degrees: Int, scratch: BitmapScratch): Bitmap {
        if (degrees == 0) return bitmap
        val transpose = degrees == 90 || degrees == 270
        val output = scratch.get(if (transpose) bitmap.height else bitmap.width,
            if (transpose) bitmap.width else bitmap.height)
        PrivacyBitmapRotation.draw(bitmap, degrees, output)
        return output
    }
}

/** No consumer retains these bitmaps: every outgoing VideoFrame owns separate I420 planes. */
private class BitmapScratch : AutoCloseable {
    private var bitmap: Bitmap? = null
    fun get(width: Int, height: Int): Bitmap {
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        return checkNotNull(bitmap)
    }
    override fun close() { bitmap?.recycle(); bitmap = null }
}

internal data class PrivacyFrameTimings(
    val inputMs: Double, val model: PrivacyModelTimings, val outputMs: Double, val totalMs: Double,
)

internal object PrivacyBitmapRotation {
    fun draw(bitmap: Bitmap, degrees: Int, output: Bitmap) {
        require(bitmap !== output && degrees in listOf(90, 180, 270))
        require(output.width == if (degrees == 180) bitmap.width else bitmap.height)
        require(output.height == if (degrees == 180) bitmap.height else bitmap.width)
        val matrix = Matrix().apply {
            setRotate(degrees.toFloat())
            when (degrees) {
                90 -> postTranslate(bitmap.height.toFloat(), 0f)
                180 -> postTranslate(bitmap.width.toFloat(), bitmap.height.toFloat())
                270 -> postTranslate(0f, bitmap.width.toFloat())
            }
        }
        Canvas(output).apply { drawColor(Color.BLACK); drawBitmap(bitmap, matrix, null) }
    }
}
