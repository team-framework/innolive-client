package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PrivacyBatchTwoRegressionDeviceTest {
    @Test fun currentDetectionSchedulesCurrentCropAndLostTrackSchedulesNothing() {
        var pending:Rect?=null
        var closed=0
        val service=object:PrivacyFaceRecognitionService {
            override val ready=true
            override val canSubmit=true
            override fun prepare()=Unit
            override fun retain() { closed++ }
            override fun release() { closed-- }
            override fun takeResult():PrivacyFaceService.Result?=null
            override fun submitRecognition(image:Bitmap,bounds:Rect,generation:Long,trackId:String,capturedAtSeconds:Double):Boolean {
                pending=Rect(bounds);image.recycle();return true
            }
        }
        val coordinator=PrivacyFaceCoordinator(service) { listOf(PrivacyRegisteredFace("test","test",FloatArray(512).apply {this[0]=1f},0)) }
        val layout=PrivacySegmentation.Letterbox(640,640)
        val box=PrivacySegmentation.Box(300f,100f,350f,150f)
        fun crop(bounds:Rect)=Bitmap.createBitmap(bounds.width(),bounds.height(),Bitmap.Config.ARGB_8888)
        assertTrue(coordinator.currentFrame(640,640,listOf(PrivacySegmentation.Detection(box,.9f,0,FloatArray(32))),layout,1_000_000_000L,::crop).isEmpty())
        assertTrue(checkNotNull(pending).left>280)
        assertEquals(1,closed)
        pending=null
        coordinator.currentFrame(640,640,emptyList(),layout,1_100_000_000L,::crop)
        assertNull(pending)
        coordinator.close();coordinator.close()
        assertEquals(0,closed)
    }

    @Test fun bgrPaddingAndFaceNormalizationMatchReferenceAfterInputChanges() {
        val input=Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888).apply {setHasAlpha(false)}
        val bgr=ByteBuffer.allocateDirect(128*128*3*4).order(ByteOrder.nativeOrder())
        val face=ByteBuffer.allocateDirect(112*112*3*4).order(ByteOrder.nativeOrder())
        try {
            repeat(3) {pattern ->
                val pixels=IntArray(112*112) {i->Color.rgb((i*13+pattern*31)%256,(i*7+pattern*11)%256,(i*19+pattern*51)%256)}
                input.setPixels(pixels,0,112,0,0,112,112)
                PrivacyNativePixels.bitmapToBgrTensor(input,bgr,128,128)
                val values=FloatArray(128*128*3).also {bgr.asFloatBuffer().get(it)}
                for(y in 0 until 128) for(x in 0 until 128) for(c in 0..2) {
                    val expected=if(y>=112 || x>=112) 0f else when(c) {0->Color.blue(pixels[y*112+x]);1->Color.green(pixels[y*112+x]);else->Color.red(pixels[y*112+x])}.toFloat()
                    assertEquals(expected,values[c*128*128+y*128+x],0f)
                }
                PrivacyNativePixels.bitmapToFaceTensor(input,face)
                val actual=FloatArray(112*112*3).also {face.asFloatBuffer().get(it)}
                assertArrayEquals(PrivacyFaceGpuEngine.normalizedPixels(pixels),actual,0f)
            }
            assertThrows(IllegalArgumentException::class.java) {PrivacyNativePixels.bitmapToBgrTensor(input,bgr,64,64)}
        } finally {input.recycle()}
    }

    @Test fun nativePreferredReleasesCpuAndFallbackUsesCurrentInputWithProtection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val source=JavaI420Buffer.allocate(640,480)
        try {
            repeat(source.dataU.capacity()) {source.dataU.put(it,128.toByte());source.dataV.put(it,128.toByte())}
            repeat(source.dataY.capacity()) {source.dataY.put(it,70.toByte())}
            PrivacyGpuFramePipeline(null,useGles3=true).use {graph ->
                PrivacyOnnxModel(context).use {model ->
                    graph.runFrame {
                        var layout=graph.prepare(source,0)
                        model.processPrepared(graph.modelBitmap,layout,1_000_000_000L,{_,_->emptySet()},gpuGraph=graph) {it}
                        assertTrue(graph.nativeInputEnabled)
                        assertFalse(model.retainsCpuSession)
                        repeat(source.dataY.capacity()) {source.dataY.put(it,210.toByte())}
                        layout=graph.prepare(source,0,readModel=false)
                        graph.disableNativeInput()
                        val mask=model.processPrepared(graph.modelBitmap,layout,1_100_000_000L,{_,_->emptySet()},gpuGraph=graph) {it}
                        assertEquals(160*160,mask.size)
                        assertTrue(model.retainsCpuSession)
                        assertFalse(checkNotNull(model.lastAnalysis).directGpuInput)
                        assertTrue(Color.red(graph.modelBitmap.getPixel(320,320))>200)
                    }
                }
            }
        } finally {source.release()}
    }

    @Test fun yuNetReusableInputMatchesAllocationPathAcrossSizeAndAlphaChanges() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PrivacyYuNetModel(context,reuseInputs=false).use {old ->
            PrivacyYuNetModel(context,reuseInputs=true).use {new ->
                for((width,height) in listOf(320 to 320,511 to 279,127 to 63,320 to 320)) {
                    val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    try {
                        for(alpha in listOf(false,true)) {
                            image.setHasAlpha(alpha)
                            image.setPixels(IntArray(width*height) {i -> Color.argb(if(alpha)128 else 255,(i*13)%256,(i*7)%256,(i*19)%256)},0,width,0,0,width,height)
                            val scale=minOf(1f,320f/maxOf(width,height))
                            val rw=maxOf(1,kotlin.math.round(width*scale).toInt()); val rh=maxOf(1,kotlin.math.round(height*scale).toInt())
                            val expected=Bitmap.createScaledBitmap(image,rw,rh,true)
                            try {
                                val resized=new.resized(image,rw,rh)
                                val a=IntArray(rw*rh).also { expected.getPixels(it,0,rw,0,0,rw,rh) }
                                val b=IntArray(rw*rh).also { resized.getPixels(it,0,rw,0,0,rw,rh) }
                                assertArrayEquals("resize ${width}x$height alpha=$alpha",a,b)
                                val tw=(rw+31)/32*32;val th=(rh+31)/32*32
                                val actual=new.inputFor(resized,tw,th).floatBuffer
                                val floats=FloatArray(actual.remaining()).also { actual.get(it) }
                                for(y in 0 until th) for(x in 0 until tw) for(c in 0..2) {
                                    val pixel=if(x<rw && y<rh) a[y*rw+x] else 0
                                    val value=when(c) {0->Color.blue(pixel);1->Color.green(pixel);else->Color.red(pixel)}.toFloat()
                                    assertEquals(value,floats[c*tw*th+y*tw+x],0f)
                                }
                            } finally {if(expected!==image)expected.recycle()}
                            assertEquals(old.oneFace(image,false),new.oneFace(image,false))
                        }
                    } finally {image.recycle()}
                }
            }
        }
    }

    @Test fun faceReusableInputMatchesCpuEmbeddingAndDoesNotRetainPreviousPixels() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val landmarks=floatArrayOf(.34f,.46f,.66f,.46f,.50f,.64f,.37f,.82f,.63f,.82f)
        val image=Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888)
        try {
            PrivacyFaceModel(context,allowGpu=false,reuseInputs=false).use {old ->
                PrivacyFaceModel(context,allowGpu=false,reuseInputs=true).use {new ->
                    repeat(3) {pattern ->
                        image.setHasAlpha(pattern==2)
                        image.setPixels(PrivacyFaceGpuEngine.syntheticPixels(pattern),0,112,0,0,112,112)
                        if(pattern==2) image.eraseColor(Color.argb(128,66,128,191))
                        assertArrayEquals(old.predict(image,landmarks),new.predict(image,landmarks),.000001f)
                    }
                }
            }
        } finally {image.recycle()}
    }
}
