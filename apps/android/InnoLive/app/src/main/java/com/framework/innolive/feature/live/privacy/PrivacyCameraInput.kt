package com.framework.innolive.feature.live.privacy

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import org.webrtc.JavaI420Buffer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the CameraX image until upload or an owned CPU copy captures its pixels. No consumer receives its mutable storage. */
internal class PrivacyCameraInput(private val image: ImageProxy) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val crop = Rect(image.cropRect)
    val width = crop.width()
    val height = crop.height()
    val rotation = image.imageInfo.rotationDegrees
    val timestampNs = image.imageInfo.timestamp
    val isClosed: Boolean get() = closed.get()

    init {
        require(width > 0 && height > 0 && crop.left >= 0 && crop.top >= 0 &&
            crop.right <= image.width && crop.bottom <= image.height && image.planes.size == 3)
        require(rotation in listOf(0,90,180,270))
    }

    fun planes(): List<PrivacyCameraPlane> {
        check(!closed.get())
        return image.planes.mapIndexed { index,plane ->
            val left=if(index==0) crop.left else crop.left/2
            val top=if(index==0) crop.top else crop.top/2
            val w=if(index==0) width else (width+1)/2
            val h=if(index==0) height else (height+1)/2
            val buffer=plane.buffer.duplicate()
            val offset=top.toLong()*plane.rowStride+left.toLong()*plane.pixelStride
            require(offset<=buffer.remaining())
            buffer.position(buffer.position()+offset.toInt())
            val source=buffer.slice()
            val needed=(h-1L)*plane.rowStride+(w-1L)*plane.pixelStride+1
            require(plane.pixelStride>0 && plane.rowStride>0 && needed<=source.remaining())
            PrivacyCameraPlane(source,plane.rowStride,plane.pixelStride,w,h)
        }
    }

    /** Only the protected CPU fallback copies camera planes into owned I420 storage. */
    fun copyI420(): JavaI420Buffer {
        val planes=planes()
        val output=JavaI420Buffer.allocate(width,height)
        try {
            val buffers=listOf(output.dataY,output.dataU,output.dataV)
            val strides=listOf(output.strideY,output.strideU,output.strideV)
            planes.forEachIndexed {index,plane -> plane.copyTo(buffers[index],strides[index])}
            return output
        } catch(error:Throwable) {output.release();throw error}
    }

    override fun close() {if(closed.compareAndSet(false,true)) image.close()}
}

internal data class PrivacyCameraPlane(val buffer:ByteBuffer,val rowStride:Int,val pixelStride:Int,
                                      val width:Int,val height:Int) {
    fun copyTo(output:ByteBuffer,stride:Int) {
        if(buffer.isDirect && output.isDirect) {
            PrivacyNativePixels.copyPlane(buffer,rowStride,pixelStride,width,height,output,stride)
        } else {
            for(y in 0 until height) for(x in 0 until width)
                output.put(y*stride+x,buffer.get(y*rowStride+x*pixelStride))
        }
    }
}
