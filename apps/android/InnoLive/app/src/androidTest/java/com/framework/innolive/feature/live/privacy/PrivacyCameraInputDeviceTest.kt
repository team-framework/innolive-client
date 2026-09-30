package com.framework.innolive.feature.live.privacy

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PrivacyCameraInputDeviceTest {
    @Test fun borrowedCameraPlanesMatchOwnedI420ThroughCropRotationAndUvLayouts() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        PrivacyGpuFramePipeline(null,useGles3=true).use {reference ->
            PrivacyGpuFramePipeline(null,useGles3=true).use {direct ->
                for(layout in listOf("NV12","NV21","NV12_TRUNCATED","NV21_TRUNCATED","PLANAR","SEPARATE_STRIDED"))
                    for(crop in listOf(Rect(0,0,97,65),Rect(3,5,94,62)))
                        for(rotation in listOf(0,90,180,270)) {
                            val fixture=PrivacyCameraFixture.create(97,65,rotation,layout,crop)
                            val owned=fixture.camera.copyI420()
                            try {
                                reference.prepare(owned,rotation)
                                val expected=IntArray(640*640).also {reference.modelBitmap.getPixels(it,0,640,0,0,640,640)}
                                val box=direct.prepare(fixture.camera,readModel=true)
                                val actual=IntArray(expected.size).also {direct.modelBitmap.getPixels(it,0,640,0,0,640,640)}
                                var error=0
                                for(i in actual.indices) for(shift in intArrayOf(0,8,16))
                                    error=maxOf(error,kotlin.math.abs(((expected[i] shr shift) and 255)-((actual[i] shr shift) and 255)))
                                assertTrue("$layout crop=$crop rotation=$rotation max channel error=$error",error<=1)
                                assertEquals(if(layout=="SEPARATE_STRIDED")2 else 0,direct.lastCameraCopiedPlanes)
                                fixture.camera.close();fixture.camera.close()
                                assertEquals(1,fixture.closes.get())
                                fixture.poison()
                                val frame=direct.finish(ByteArray(160*160),box,crop.width(),crop.height())
                                try {assertEquals(crop.width(),frame.width);assertEquals(crop.height(),frame.height)} finally {frame.release()}
                                assertThrows(IllegalStateException::class.java) {fixture.camera.copyI420()}
                            } finally {owned.release();fixture.camera.close()}
                        }
            }
        }
    }

    @Test fun comparePooledI420WithBorrowedCameraUpload() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        for((width,height) in listOf(1920 to 1080,1280 to 720)) {
            val fixture=PrivacyCameraFixture.create(width,height,90,"NV21_TRUNCATED")
            val owned=fixture.camera.copyI420()
            try {
                PrivacyGpuFramePipeline(null,useGles3=true).use {graph ->
                    val pooled=mutableListOf<Double>();val direct=mutableListOf<Double>()
                    val planes=fixture.camera.planes()
                    val buffers=listOf(owned.dataY,owned.dataU,owned.dataV)
                    val strides=listOf(owned.strideY,owned.strideU,owned.strideV)
                    repeat(34) {index ->
                        fun old() {
                            val started=System.nanoTime()
                            planes.forEachIndexed {i,plane ->plane.copyTo(buffers[i],strides[i])}
                            graph.prepare(owned,90,readModel=false)
                            pooled+=(System.nanoTime()-started)/1e6
                        }
                        fun next() {
                            val started=System.nanoTime();graph.prepare(fixture.camera,readModel=false)
                            direct+=(System.nanoTime()-started)/1e6
                            assertEquals(0,graph.lastCameraCopiedPlanes)
                        }
                        if(index%2==0) {old();next()} else {next();old()}
                    }
                    Log.i("PrivacyStages","stage=camera_input size=${width}x$height pooled_i420_upload_submit_p50_ms=${pooled.drop(4).sorted()[15]} borrowed_upload_submit_p50_ms=${direct.drop(4).sorted()[15]} copied_planes=0")
                }
            } finally {owned.release();fixture.camera.close()}
        }
    }

    @Test fun uploadOwnsPixelsBeforeClosedCameraStorageCanBeOverwritten() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val fixture=PrivacyCameraFixture.create(64,48,90,"NV21")
        val owned=fixture.camera.copyI420()
        try {
            PrivacyGpuFramePipeline(null,useGles3=true).use {graph ->
                val layout=graph.prepare(fixture.camera,readModel=false)
                fixture.camera.close();fixture.poison()
                val direct=graph.finish(ByteArray(160*160),layout,64,48)
                val first=checkNotNull(direct.toI420())
                try {
                    graph.prepare(owned,90,readModel=false)
                    val reference=graph.finish(ByteArray(160*160),layout,64,48)
                    val second=checkNotNull(reference.toI420())
                    try {
                        for(y in 0 until 48) for(x in 0 until 64)
                            assertEquals(first.dataY.get(y*first.strideY+x),second.dataY.get(y*second.strideY+x))
                    } finally {second.release();reference.release()}
                } finally {first.release();direct.release()}
            }
        } finally {owned.release();fixture.camera.close()}
    }
}

internal class PrivacyCameraFixture(val camera:PrivacyCameraInput,val closes:AtomicInteger,
                                   private val storage:List<ByteBuffer>) {
    fun poison() {storage.forEach {bytes -> repeat(bytes.capacity()) {bytes.put(it,0)}}}
    companion object {
        fun create(width:Int,height:Int,rotation:Int=0,layout:String="NV21",
                   crop:Rect=Rect(0,0,width,height)):PrivacyCameraFixture {
            val closes=AtomicInteger()
            val kind=layout.removeSuffix("_TRUNCATED")
            val truncated=layout.endsWith("_TRUNCATED")
            val offset=7
            val yStride=width+15
            val y=ByteBuffer.allocateDirect(offset+(height-1)*yStride+width)
            for(row in 0 until height) for(x in 0 until width) y.put(offset+row*yStride+x,(16+(x*7+row*13)%219).toByte())
            val cw=(width+1)/2;val ch=(height+1)/2
            val pixelStride=if(kind=="PLANAR")1 else 2
            val uvStride=cw*pixelStride+14
            val uvBytes=offset+(ch-1)*uvStride+cw*pixelStride
            val shared=ByteBuffer.allocateDirect(uvBytes)
            val u=if(kind=="NV12"||kind=="NV21")shared else ByteBuffer.allocateDirect(uvBytes)
            val v=if(kind=="NV12"||kind=="NV21")shared else ByteBuffer.allocateDirect(uvBytes)
            val uStart=offset+if(kind=="NV21")1 else 0
            val vStart=offset+if(kind=="NV12")1 else 0
            for(row in 0 until ch) for(x in 0 until cw) {
                u.put(uStart+row*uvStride+x*pixelStride,(100+(x+row)%31).toByte())
                v.put(vStart+row*uvStride+x*pixelStride,(135+(x*3+row)%31).toByte())
            }
            fun plane(bytes:ByteBuffer,start:Int,stride:Int,pixel:Int):ImageProxy.PlaneProxy =
                Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader,arrayOf(ImageProxy.PlaneProxy::class.java)) {_,method,_ ->
                    when(method.name) {"getBuffer"->bytes.duplicate().apply {
                        position(start)
                        if(truncated && pixel==2) limit(start+(ch-1)*stride+(cw-1)*pixel+1)
                    };"getRowStride"->stride;"getPixelStride"->pixel;else->error(method.name)}
                } as ImageProxy.PlaneProxy
            val planes=arrayOf(plane(y,offset,yStride,1),plane(u,uStart,uvStride,pixelStride),plane(v,vStart,uvStride,pixelStride))
            val info=Proxy.newProxyInstance(ImageInfo::class.java.classLoader,arrayOf(ImageInfo::class.java)) {_,method,_ ->
                when(method.name) {"getRotationDegrees"->rotation;"getTimestamp"->123_456L;else->error(method.name)}
            } as ImageInfo
            val image=Proxy.newProxyInstance(ImageProxy::class.java.classLoader,arrayOf(ImageProxy::class.java)) {_,method,_ ->
                when(method.name) {"getWidth"->width;"getHeight"->height;"getCropRect"->crop;"getPlanes"->planes;"getImageInfo"->info;"close"->{closes.incrementAndGet();Unit};else->error(method.name)}
            } as ImageProxy
            return PrivacyCameraFixture(PrivacyCameraInput(image),closes,listOf(y,u,v).distinct())
        }
    }
}
