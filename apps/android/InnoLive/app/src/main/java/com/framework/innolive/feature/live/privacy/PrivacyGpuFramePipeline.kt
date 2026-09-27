package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.opengl.GLES30.GL_UNPACK_ROW_LENGTH
import android.util.Log
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.webrtc.EglBase
import org.webrtc.GlShader
import org.webrtc.GlUtil
import org.webrtc.TextureBufferImpl
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.YuvConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max

/** Serial GL graph. Only 640px model input and requested face crops cross back to CPU.
 * Output textures remain owned until every renderer/encoder releases its frame. */
internal class PrivacyGpuFramePipeline(sharedContext: EglBase.Context?, useGles3: Boolean = false,
    private val useFence: Boolean = true, private val compactMask: Boolean = true) : AutoCloseable {
    internal var lastFenceUsed = false
        private set
    private val thread = HandlerThread("privacy-image-gpu").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglBase
    private lateinit var converter: YuvConverter
    private val shaders = mutableMapOf<String, GlShader>()
    private val targets = mutableMapOf<String, Target>()
    private val planeTextures = IntArray(3)
    private val maskTexture = IntArray(1)
    private var maskTextureReady = false
    private val packedPlanes = arrayOfNulls<ByteBuffer>(3)
    private val planeSizes = arrayOfNulls<Pair<Int, Int>>(3)
    private val planeFormats = IntArray(3)
    private var rowLengthSupported = false
    private var chromaMode = 0
    private val cameraLastPair=ByteBuffer.allocateDirect(2)
    private var cameraLayoutLogged=false
    internal var lastCameraCopiedPlanes = 0
        private set
    private val maskPixels = ByteBuffer.allocateDirect(160 * 160 * if(compactMask) 1 else 4)
    private var cropPixels: ByteBuffer? = null
    private val outputPool = mutableListOf<Target>()
    private val leased = mutableSetOf<Target>()
    private var closing = false
    private var nativeModel = 0L
    private var nativeChecked = false
    private var nativeValidated = false
    val nativeInputEnabled: Boolean get() = nativeValidated
    internal var nativeInputUsesManagedSync = false
        private set
    private val closeRequested = AtomicBoolean(false)
    private var width = 0
    private var height = 0
    private var rotation = 0
    private val modelPixels = ByteBuffer.allocateDirect(640 * 640 * 4)
    val modelBitmap: Bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)

    init {
        try {
            onGl {
                egl = if (useGles3) try {
                    EglBase.create(sharedContext, EglBase.configBuilder().setOpenGlesVersion(3)
                        .setSupportsPixelBuffer(true).createConfigAttributes())
                } catch (_: RuntimeException) { EglBase.create(sharedContext, EglBase.CONFIG_PIXEL_BUFFER) }
                else EglBase.create(sharedContext, EglBase.CONFIG_PIXEL_BUFFER)
                egl.createDummyPbufferSurface(); egl.makeCurrent()
                converter = YuvConverter()
                rowLengthSupported = glGetString(GL_VERSION)?.contains("OpenGL ES 3") == true
                glGenTextures(3, planeTextures, 0)
                glGenTextures(1, maskTexture, 0)
            }
        } catch (error: Throwable) {
            onGl { if (::egl.isInitialized) egl.release() }
            modelBitmap.recycle(); thread.quitSafely()
            throw error
        }
    }

    private fun <T> onGl(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val complete = CountDownLatch(1)
        var result: Result<T>? = null
        check(handler.post { result = runCatching(block); complete.countDown() }) { "GPU graph is closed" }
        ThreadUtils.awaitUninterruptibly(complete)
        return checkNotNull(result).getOrThrow()
    }

    fun prepare(source: VideoFrame.I420Buffer, rotation: Int, readModel: Boolean = true): PrivacySegmentation.Letterbox = onGl {
        check(!closing)
        if (leased.size >= 6) throw PrivacyGpuBackpressureException()
        require(rotation in listOf(0, 90, 180, 270))
        this.rotation = rotation
        chromaMode = 0
        width = if (rotation % 180 == 0) source.width else source.height
        height = if (rotation % 180 == 0) source.height else source.width
        val buffers = arrayOf(source.dataY, source.dataU, source.dataV)
        val strides = intArrayOf(source.strideY, source.strideU, source.strideV)
        val sizes = arrayOf(source.width to source.height,
            (source.width + 1) / 2 to (source.height + 1) / 2,
            (source.width + 1) / 2 to (source.height + 1) / 2)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        for (i in 0..2) {
            val (w, h) = sizes[i]
            val pixels = if (strides[i] == w) buffers[i].duplicate().apply { position(0) } else {
                val packed = packedPlanes[i]?.takeIf { it.capacity() >= w * h }
                    ?: ByteBuffer.allocateDirect(w * h).also { packedPlanes[i] = it }
                PrivacyNativePixels.copyPlane(buffers[i].duplicate().apply { position(0) },
                    strides[i], 1, w, h, packed, w)
                packed.apply { position(0) }
            }
            glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, planeTextures[i])
            textureParameters(GL_LINEAR)
            if (planeSizes[i] != (w to h) || planeFormats[i] != GL_LUMINANCE) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, w, h, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, pixels)
                planeSizes[i] = w to h
                planeFormats[i] = GL_LUMINANCE
            } else glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_LUMINANCE, GL_UNSIGNED_BYTE, pixels)
        }
        draw("yuv", YUV, target("upright", width, height), cameraTextures()) { program ->
            glUniform1i(program.getUniformLocation("rotation"), rotation)
            glUniform1i(program.getUniformLocation("chromaMode"), chromaMode)
        }
        val layout = PrivacySegmentation.Letterbox(width, height)
        draw("letterbox", LETTERBOX, target("model", 640, 640), listOf(targets.getValue("upright").texture)) { p ->
            glUniform4f(p.getUniformLocation("box"), layout.left / 640f, layout.top / 640f,
                layout.resizedWidth / 640f, layout.resizedHeight / 640f)
        }
        if (readModel) {
            readPixels(targets.getValue("model"), modelPixels)
            modelPixels.rewind(); modelBitmap.copyPixelsFromBuffer(modelPixels)
        }
        layout
    }

    /** Uploads camera-owned planes directly. UV is one interleaved texture when views share storage. */
    fun prepare(camera: PrivacyCameraInput,readModel:Boolean=true): PrivacySegmentation.Letterbox = onGl {
        check(!closing)
        if(leased.size>=6) throw PrivacyGpuBackpressureException()
        rotation=camera.rotation
        width=if(rotation%180==0) camera.width else camera.height
        height=if(rotation%180==0) camera.height else camera.width
        val planes=camera.planes()
        lastCameraCopiedPlanes=0
        val u=planes[1];val v=planes[2]
        chromaMode=if(u.pixelStride==2 && v.pixelStride==2 &&
            (rowLengthSupported || (u.rowStride==u.width*2 && v.rowStride==v.width*2)))
            PrivacyNativePixels.cameraChromaLayout(u.buffer,v.buffer,u.rowStride,v.rowStride,u.width,u.height) else 0
        uploadCameraPlane(0,planes[0],1)
        if(!cameraLayoutLogged) {
            cameraLayoutLogged=true
            Log.i("PrivacyPipeline","camera_uv_layout=$chromaMode strides=${u.rowStride}/${v.rowStride} pixels=${u.pixelStride}/${v.pixelStride} capacities=${u.buffer.remaining()}/${v.buffer.remaining()} size=${u.width}x${u.height}")
        }
        if(chromaMode!=0) {
            val first=if(chromaMode==1) u else v
            val lastIndex=(first.height-1)*first.rowStride+(first.width-1)*2
            val last=if(lastIndex+2>first.buffer.remaining()) cameraLastPair.apply {
                clear();put(first.buffer.get(lastIndex))
                put((if(chromaMode==1) v else u).buffer.get(lastIndex));flip()
            } else null
            uploadCameraPlane(1,first,2,last)
        }
        else {uploadCameraPlane(1,u,1);uploadCameraPlane(2,v,1)}
        draw("yuv",YUV,target("upright",width,height),cameraTextures()) {p ->
            glUniform1i(p.getUniformLocation("rotation"),rotation)
            glUniform1i(p.getUniformLocation("chromaMode"),chromaMode)
        }
        val layout=PrivacySegmentation.Letterbox(width,height)
        draw("letterbox",LETTERBOX,target("model",640,640),listOf(targets.getValue("upright").texture)) {p ->
            glUniform4f(p.getUniformLocation("box"),layout.left/640f,layout.top/640f,
                layout.resizedWidth/640f,layout.resizedHeight/640f)
        }
        if(readModel) readModelInput()
        layout
    }

    private fun cameraTextures() = if(chromaMode==0) planeTextures.toList()
        else listOf(planeTextures[0],planeTextures[1],planeTextures[1])

    private fun uploadCameraPlane(index:Int,plane:PrivacyCameraPlane,components:Int,lastPair:ByteBuffer?=null) {
        val direct=plane.buffer.isDirect && plane.pixelStride==components &&
            (plane.rowStride==plane.width*components || (rowLengthSupported && plane.rowStride%components==0))
        val pixels=if(direct) plane.buffer.duplicate() else {
            require(components==1)
            val size=plane.width*plane.height
            val packed=packedPlanes[index]?.takeIf {it.capacity()>=size}
                ?: ByteBuffer.allocateDirect(size).also {packedPlanes[index]=it}
            plane.copyTo(packed,plane.width);lastCameraCopiedPlanes++
            packed.duplicate().apply {position(0)}
        }
        val format=if(components==2) GL_LUMINANCE_ALPHA else GL_LUMINANCE
        glPixelStorei(GL_UNPACK_ALIGNMENT,1)
        if(rowLengthSupported) glPixelStorei(GL_UNPACK_ROW_LENGTH,if(direct) plane.rowStride/components else 0)
        try {
            glActiveTexture(GL_TEXTURE0+index);glBindTexture(GL_TEXTURE_2D,planeTextures[index]);textureParameters(GL_LINEAR)
            val allocate=planeSizes[index]!=(plane.width to plane.height) || planeFormats[index]!=format
            if(allocate) {
                glTexImage2D(GL_TEXTURE_2D,0,format,plane.width,plane.height,0,format,GL_UNSIGNED_BYTE,
                    if(lastPair==null) pixels else null)
                planeSizes[index]=plane.width to plane.height;planeFormats[index]=format
            }
            if(lastPair!=null) {
                // CameraX may hide the final unused interleaved byte in the base plane view.
                // Upload bounded rows, then assemble only the last pair from the two valid views.
                if(plane.height>1) glTexSubImage2D(GL_TEXTURE_2D,0,0,0,plane.width,plane.height-1,format,GL_UNSIGNED_BYTE,pixels)
                val lastRow=pixels.duplicate().apply {position((plane.height-1)*plane.rowStride)}.slice()
                if(plane.width>1) glTexSubImage2D(GL_TEXTURE_2D,0,0,plane.height-1,plane.width-1,1,format,GL_UNSIGNED_BYTE,lastRow)
                if(rowLengthSupported) glPixelStorei(GL_UNPACK_ROW_LENGTH,0)
                glTexSubImage2D(GL_TEXTURE_2D,0,plane.width-1,plane.height-1,1,1,format,GL_UNSIGNED_BYTE,lastPair)
            } else if(!allocate) glTexSubImage2D(GL_TEXTURE_2D,0,0,0,plane.width,plane.height,format,GL_UNSIGNED_BYTE,pixels)
            GlUtil.checkNoGLES2Error("privacy camera plane upload")
        } finally {if(rowLengthSupported) glPixelStorei(GL_UNPACK_ROW_LENGTH,0)}
    }

    fun crop(bounds: Rect): Bitmap = onGl {
        require(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height
            && bounds.width() > 0 && bounds.height() > 0)
        val cropped = target("crop", bounds.width(), bounds.height())
        draw("crop", CROP, cropped, listOf(targets.getValue("upright").texture)) { p ->
            glUniform4f(p.getUniformLocation("box"), bounds.left.toFloat() / width,
                bounds.top.toFloat() / height, bounds.width().toFloat() / width, bounds.height().toFloat() / height)
        }
        val size = bounds.width() * bounds.height() * 4
        val pixels = cropPixels?.takeIf { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(size).also { cropPixels = it }
        readPixels(cropped, pixels)
        Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888).also {
            pixels.rewind(); it.copyPixelsFromBuffer(pixels)
        }
    }

    /** Owned current pixels for a protected CPU fallback after the camera image has been returned. */
    fun copyUpright(): Bitmap = onGl {
        val pixels=ByteBuffer.allocateDirect(width*height*4)
        readPixels(targets.getValue("upright"),pixels)
        Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {
            pixels.rewind();it.copyPixelsFromBuffer(pixels)
        }
    }

    internal fun createNativeInputModel(path: String) = onGl {
        check(nativeModel == 0L && !closing)
        egl.makeCurrent()
        try { nativeModel = PrivacyNativeGpuModel.create(path) } finally { egl.makeCurrent() }
    }

    internal fun predictNativeInput(useFence:Boolean=true): Pair<FloatArray, FloatArray> = onGl {
        check(nativeModel != 0L && !closing)
        egl.makeCurrent()
        try {
            val values = PrivacyNativeGpuModel.predict(nativeModel, targets.getValue("model").texture,useFence)
            nativeInputUsesManagedSync=PrivacyNativeGpuModel.usesManagedInputSync(nativeModel)
            values[0] to values[1]
        } finally { egl.makeCurrent() }
    }

    private fun readModelInput() = onGl {
        readPixels(targets.getValue("model"), modelPixels)
        modelPixels.rewind(); modelBitmap.copyPixelsFromBuffer(modelPixels)
    }

    private fun uploadModelImage(bitmap: Bitmap) = onGl {
        glBindTexture(GL_TEXTURE_2D, targets.getValue("model").texture)
        GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bitmap)
        GlUtil.checkNoGLES2Error("privacy GPU validation input")
    }

    /** Re-submit the real camera's YUV/letterbox work so readback timing includes its GPU dependency. */
    private fun redrawCameraInput() = onGl {
        draw("yuv",YUV,targets.getValue("upright"),cameraTextures()) { p ->
            glUniform1i(p.getUniformLocation("rotation"),rotation)
            glUniform1i(p.getUniformLocation("chromaMode"),chromaMode)
        }
        val layout=PrivacySegmentation.Letterbox(width,height)
        draw("letterbox",LETTERBOX,targets.getValue("model"),listOf(targets.getValue("upright").texture)) { p ->
            glUniform4f(p.getUniformLocation("box"),layout.left/640f,layout.top/640f,
                layout.resizedWidth/640f,layout.resizedHeight/640f)
        }
    }

    /** Calibrate once with the same RGB pixels, including the eliminated readback/tensor copies. */
    fun validateNativeInput(context: Context, baseline: (FloatArray) -> Pair<FloatArray, FloatArray>) {
        if (nativeChecked) return
        nativeChecked = true
        val original = modelBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val probe = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val tensor = ByteBuffer.allocateDirect(3 * 640 * 640 * 4).order(ByteOrder.nativeOrder())
        val input = FloatArray(3 * 640 * 640)
        try {
            createNativeInputModel(PrivacyDetectorGpuEngine.verifiedFile(context).absolutePath)
            for (pattern in 0..2) {
                val colors = IntArray(640 * 640) { index -> when (pattern) {
                    0 -> android.graphics.Color.rgb(128,128,128)
                    1 -> android.graphics.Color.rgb(index % 640 * 255 / 639,128,64)
                    else -> android.graphics.Color.rgb(index * 13 % 255,index * 7 % 255,index * 19 % 255)
                } }
                probe.setPixels(colors,0,640,0,0,640,640)
                uploadModelImage(probe)
                PrivacyNativePixels.bitmapToTensor(probe,tensor); tensor.asFloatBuffer().get(input)
                val expected = baseline(input)
                val actual = predictNativeInput()
                fun error(a: FloatArray,b: FloatArray): Float {
                    check(a.size==b.size && PrivacyNativePixels.finiteFloats(a) && PrivacyNativePixels.finiteFloats(b))
                    var maximum=0f; for(i in a.indices) maximum=maxOf(maximum,kotlin.math.abs(a[i]-b[i])); return maximum
                }
                check(error(actual.first,expected.first)<.05f && error(actual.second,expected.second)<.01f) { "GPU buffer output mismatch" }
            }
            val oldTimes=mutableListOf<Long>();val newTimes=mutableListOf<Long>()
            fun legacy() {
                val tick=System.nanoTime();redrawCameraInput();readModelInput()
                PrivacyNativePixels.bitmapToTensor(modelBitmap,tensor);tensor.asFloatBuffer().get(input)
                baseline(input);oldTimes+=System.nanoTime()-tick
            }
            fun direct() {
                val tick=System.nanoTime();redrawCameraInput()
                val output=predictNativeInput()
                check(PrivacyNativePixels.finiteFloats(output.first) && PrivacyNativePixels.finiteFloats(output.second))
                newTimes+=System.nanoTime()-tick
            }
            repeat(3) {legacy();direct()};oldTimes.clear();newTimes.clear()
            repeat(12) {sample -> if(sample%2==0) {legacy();direct()} else {direct();legacy()} }
            val old=oldTimes.sorted()[oldTimes.size/2]; val direct=newTimes.sorted()[newTimes.size/2]
            val wins=oldTimes.indices.count {newTimes[it]<oldTimes[it]}
            // Prefer the supported, output-validated zero-readback path. Startup DVFS samples are
            // diagnostics, not a permanent veto of the camera's steady-state input path.
            nativeValidated=true
            Log.i("PrivacyDetector","gpu_input_validated legacy_ms=${old/1e6} direct_ms=${direct/1e6} wins=$wins/12 managed_input_sync=$nativeInputUsesManagedSync")
        } catch(error: Exception) {
            onGl { if(nativeModel!=0L) { PrivacyNativeGpuModel.destroy(nativeModel);nativeModel=0L;egl.makeCurrent() } }
            Log.i("PrivacyDetector","gpu_input_rejected type=${error.javaClass.simpleName} detail=${error.message?.take(120)}")
        } finally {
            uploadModelImage(original)
            original.copyPixelsToBuffer(modelPixels.apply { clear() });modelPixels.rewind();modelBitmap.copyPixelsFromBuffer(modelPixels)
            original.recycle();probe.recycle()
        }
    }

    /** Restore the current GPU image for the protected fallback, never a previous input bitmap. */
    fun disableNativeInput() {
        nativeValidated=false
        onGl { if(nativeModel!=0L) { PrivacyNativeGpuModel.destroy(nativeModel);nativeModel=0L;egl.makeCurrent() } }
        readModelInput()
    }

    fun finish(mask: ByteArray, layout: PrivacySegmentation.Letterbox,
               sensorWidth: Int, sensorHeight: Int): VideoFrame.TextureBuffer = onGl {
        check(!closing)
        require(layout.sourceWidth == width && layout.sourceHeight == height && mask.size == 160 * 160)
        val output = outputPool.firstOrNull { it !in leased && it.width == sensorWidth && it.height == sensorHeight }
            ?: run {
                // Geometry changes may retire free targets, never frames retained by WebRTC.
                outputPool.filter { it !in leased }.toList().forEach { it.close(); outputPool.remove(it) }
                if (leased.size >= 6) throw PrivacyGpuBackpressureException()
                Target(sensorWidth, sensorHeight).also { outputPool.add(it) }
            }
        val original = targets.getValue("upright")
        if (mask.any { it.toInt() != 0 }) {
            val alpha = uploadMask(mask)
            val quarterW = max(1, ceil(width / 4.0).toInt())
            val quarterH = max(1, ceil(height / 4.0).toInt())
            val pixelated = target("pixelated", quarterW, quarterH)
            draw("pixelate", PIXELATE, pixelated, listOf(original.texture)) { p ->
                glUniform2f(p.getUniformLocation("imageSize"), width.toFloat(), height.toFloat())
            }
            val horizontal = target("blur-x", quarterW, quarterH)
            val vertical = target("blur-y", quarterW, quarterH)
            draw("blur", BLUR, horizontal, listOf(pixelated.texture)) { p ->
                glUniform2f(p.getUniformLocation("stepSize"), 1f / quarterW, 0f)
            }
            draw("blur", BLUR, vertical, listOf(horizontal.texture)) { p ->
                glUniform2f(p.getUniformLocation("stepSize"), 0f, 1f / quarterH)
            }
            draw("composite", COMPOSITE, output, listOf(original.texture, vertical.texture, alpha.texture)) { p ->
                glUniform1i(p.getUniformLocation("rotation"), rotation)
                glUniform4f(p.getUniformLocation("box"), layout.left / 640f, layout.top / 640f,
                    layout.resizedWidth / 640f, layout.resizedHeight / 640f)
            }
        } else {
            draw("restore", RESTORE, output, listOf(original.texture)) { p ->
                glUniform1i(p.getUniformLocation("rotation"), rotation)
            }
        }
        GlUtil.checkNoGLES2Error("privacy GPU output")
        val fence = if (useFence) PrivacyNativeGpuFence.create() else 0L
        lastFenceUsed = fence != 0L
        if (fence == 0L) glFinish()
        leased.add(output)
        val matrix = Matrix().apply { preTranslate(0f, 1f); preScale(1f, -1f) }
        val buffer = TextureBufferImpl(sensorWidth, sensorHeight, VideoFrame.TextureBuffer.Type.RGB,
            output.texture, matrix, handler, converter) {
            handler.post {
                if (fence != 0L) PrivacyNativeGpuFence.destroy(fence)
                leased.remove(output)
                if (closing) { output.close(); outputPool.remove(output); releaseIfIdle() }
            }
        }
        if (fence == 0L) buffer else PrivacyFencedTexture(buffer, fence)
    }

    private fun uploadMask(mask: ByteArray): Target {
        val bytes = maskPixels.apply { clear() }
        val rawTexture = if(compactMask) {
            bytes.put(mask);bytes.rewind()
            glBindTexture(GL_TEXTURE_2D,maskTexture[0]);textureParameters(GL_LINEAR)
            glPixelStorei(GL_UNPACK_ALIGNMENT,1)
            if(!maskTextureReady) {
                glTexImage2D(GL_TEXTURE_2D,0,GL_LUMINANCE,160,160,0,GL_LUMINANCE,GL_UNSIGNED_BYTE,bytes)
                maskTextureReady=true
            } else glTexSubImage2D(GL_TEXTURE_2D,0,0,0,160,160,GL_LUMINANCE,GL_UNSIGNED_BYTE,bytes)
            maskTexture[0]
        } else {
            val raw=target("mask-raw",160,160)
            for(value in mask) {bytes.put(value);bytes.put(value);bytes.put(value);bytes.put(-1)}
            bytes.rewind();glBindTexture(GL_TEXTURE_2D,raw.texture)
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,160,160,GL_RGBA,GL_UNSIGNED_BYTE,bytes)
            raw.texture
        }
        // Same 2px opaque core, 4px outer dilation, sigma 1.5 feather as iOS.
        val core = target("mask-core", 160, 160)
        val outer = target("mask-outer", 160, 160)
        val temp = target("mask-temp", 160, 160)
        val blurred = target("mask-blur", 160, 160)
        val alpha = target("mask-alpha", 160, 160)
        for ((radius, output) in listOf(2 to core, 4 to outer)) {
            draw("dilate-x", DILATE, temp, listOf(rawTexture)) { p ->
                glUniform2f(p.getUniformLocation("stepSize"), 1f / 160, 0f)
                glUniform1i(p.getUniformLocation("radius"), radius)
            }
            draw("dilate-y", DILATE, output, listOf(temp.texture)) { p ->
                glUniform2f(p.getUniformLocation("stepSize"), 0f, 1f / 160)
                glUniform1i(p.getUniformLocation("radius"), radius)
            }
        }
        draw("feather", FEATHER, temp, listOf(outer.texture)) { p ->
            glUniform2f(p.getUniformLocation("stepSize"), 1f / 160, 0f)
        }
        draw("feather", FEATHER, blurred, listOf(temp.texture)) { p ->
            glUniform2f(p.getUniformLocation("stepSize"), 0f, 1f / 160)
        }
        draw("mask-max", MASK_MAX, alpha, listOf(core.texture, blurred.texture))
        return alpha
    }

    private fun target(name: String, w: Int, h: Int): Target {
        val old = targets[name]
        if (old?.width == w && old.height == h) return old
        old?.close()
        return Target(w, h).also { targets[name] = it }
    }

    private fun draw(name: String, fragment: String, output: Target, textures: List<Int>,
                     uniforms: (GlShader) -> Unit = {}) {
        val p = shaders.getOrPut(name) { GlShader(VERTEX, HEADER + fragment) }
        glBindFramebuffer(GL_FRAMEBUFFER, output.framebuffer)
        glDisable(GL_BLEND)
        glDisable(GL_SCISSOR_TEST)
        glViewport(0, 0, output.width, output.height)
        p.useProgram()
        p.setVertexAttribArray("position", 2, VERTICES)
        for ((i, texture) in textures.withIndex()) {
            glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, texture)
            glUniform1i(p.getUniformLocation("tex$i"), i)
        }
        uniforms(p)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkNoGLES2Error("privacy $name")
    }

    private fun readPixels(target: Target, pixels: ByteBuffer) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
        pixels.clear()
        glReadPixels(0, 0, target.width, target.height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        GlUtil.checkNoGLES2Error("privacy read crop/model")
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        onGl {
            if (!closing) {
                closing = true
                if (nativeModel != 0L) { PrivacyNativeGpuModel.destroy(nativeModel); nativeModel = 0L; egl.makeCurrent() }
                targets.values.forEach { it.close() }; targets.clear()
                glDeleteTextures(3, planeTextures, 0)
                glDeleteTextures(1, maskTexture, 0)
                shaders.values.forEach { it.release() }; shaders.clear()
                modelBitmap.recycle()
                outputPool.filter { it !in leased }.toList().forEach { it.close(); outputPool.remove(it) }
                releaseIfIdle()
            }
        }
    }

    private fun releaseIfIdle() {
        if (closing && leased.isEmpty()) {
            converter.release(); egl.release(); thread.quitSafely()
        }
    }

    private class Target(val width: Int, val height: Int) : AutoCloseable {
        val texture: Int
        val framebuffer: Int
        init {
            val ids = IntArray(1)
            glGenTextures(1, ids, 0); texture = ids[0]
            glBindTexture(GL_TEXTURE_2D, texture); textureParameters(GL_LINEAR)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glGenFramebuffers(1, ids, 0); framebuffer = ids[0]
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                close(); error("Incomplete privacy framebuffer")
            }
        }
        override fun close() { glDeleteFramebuffers(1, intArrayOf(framebuffer), 0); glDeleteTextures(1, intArrayOf(texture), 0) }
    }

    companion object {
        private fun textureParameters(filter: Int) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        private val VERTICES = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); rewind() }
        private const val VERTEX = "attribute vec2 position; varying vec2 uv; void main(){ uv=(position+1.0)*0.5; gl_Position=vec4(position,0.0,1.0); }"
        private const val HEADER = "precision highp float; varying vec2 uv;\n"
        private const val FORWARD = "vec2 upright(vec2 p){ if(rotation==90) return vec2(1.0-p.y,p.x); if(rotation==180) return 1.0-p; if(rotation==270) return vec2(p.y,1.0-p.x); return p; }"
        private const val YUV = """
            uniform sampler2D tex0,tex1,tex2; uniform int rotation,chromaMode;
            vec2 sensor(vec2 p){ if(rotation==90) return vec2(p.y,1.0-p.x); if(rotation==180) return 1.0-p; if(rotation==270) return vec2(1.0-p.y,p.x); return p; }
            void main(){ vec2 p=sensor(uv); float y=max(0.0,texture2D(tex0,p).r*255.0-16.0)*298.0/256.0;
              vec4 chroma=texture2D(tex1,p);
              float u=(chromaMode==2?chroma.a:chroma.r)*255.0-128.0;
              float v=(chromaMode==1?chroma.a:(chromaMode==2?chroma.r:texture2D(tex2,p).r))*255.0-128.0;
              gl_FragColor=vec4(clamp(vec3(y+409.0/256.0*v,y-100.0/256.0*u-208.0/256.0*v,y+516.0/256.0*u)/255.0,0.0,1.0),1.0); }
        """
        private const val LETTERBOX = """
            uniform sampler2D tex0; uniform vec4 box;
            void main(){ vec2 p=(uv-box.xy)/box.zw; gl_FragColor=(p.x<0.0||p.y<0.0||p.x>1.0||p.y>1.0)?vec4(vec3(114.0/255.0),1.0):texture2D(tex0,p); }
        """
        private const val CROP = "uniform sampler2D tex0; uniform vec4 box; void main(){ gl_FragColor=texture2D(tex0,box.xy+uv*box.zw); }"
        private const val RESTORE = "uniform sampler2D tex0; uniform int rotation; " + FORWARD + "void main(){ gl_FragColor=texture2D(tex0,upright(uv)); }"
        private const val PIXELATE = """
            uniform sampler2D tex0; uniform vec2 imageSize;
            void main(){ vec2 p=(floor(uv*imageSize/24.0)+0.5)*24.0/imageSize; gl_FragColor=texture2D(tex0,clamp(p,0.0,1.0)); }
        """
        // Gaussian sigma 6 at quarter resolution = sigma 24 at full resolution.
        private const val BLUR = """
            uniform sampler2D tex0; uniform vec2 stepSize;
            void main(){ vec4 value=vec4(0.0); float sum=0.0;
              for(int i=-18;i<=18;i++){ float w=exp(-float(i*i)/72.0); value+=texture2D(tex0,uv+float(i)*stepSize)*w; sum+=w; }
              gl_FragColor=value/sum; }
        """
        private const val DILATE = """
            uniform sampler2D tex0; uniform vec2 stepSize; uniform int radius;
            void main(){ float a=0.0; for(int i=-4;i<=4;i++){ if(i>=-radius && i<=radius) a=max(a,texture2D(tex0,uv+float(i)*stepSize).r); } gl_FragColor=vec4(vec3(a),1.0); }
        """
        private const val FEATHER = """
            uniform sampler2D tex0; uniform vec2 stepSize;
            void main(){ float value=0.0; float sum=0.0; for(int i=-5;i<=5;i++){ float w=exp(-float(i*i)/4.5); value+=texture2D(tex0,uv+float(i)*stepSize).r*w; sum+=w; } gl_FragColor=vec4(vec3(value/sum),1.0); }
        """
        private const val MASK_MAX = "uniform sampler2D tex0,tex1; void main(){ gl_FragColor=max(texture2D(tex0,uv),texture2D(tex1,uv)); }"
        private const val COMPOSITE = "uniform sampler2D tex0,tex1,tex2; uniform vec4 box; uniform int rotation; " + FORWARD +
            "void main(){ vec2 p=upright(uv); float a=texture2D(tex2,box.xy+p*box.zw).r; gl_FragColor=mix(texture2D(tex0,p),texture2D(tex1,p),a); }"
    }
}

/** A slow encoder/renderer holds all output slots; drop input instead of overwriting or growing memory. */
internal class PrivacyGpuBackpressureException : RuntimeException()
