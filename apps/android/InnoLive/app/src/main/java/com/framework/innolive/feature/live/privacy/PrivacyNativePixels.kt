package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import java.nio.ByteBuffer
import org.webrtc.VideoFrame
import org.webrtc.YuvHelper
import com.framework.innolive.feature.live.PrivacyCameraBufferPool

/** Serially owned scratch buffer; native conversion never retains a camera buffer. */
internal class PrivacyPixelConverter : AutoCloseable {
    private var rgba: ByteBuffer? = null
    private val outputPool=PrivacyCameraBufferPool()

    fun toBitmap(source: VideoFrame.I420Buffer): Bitmap {
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            toBitmap(source, bitmap)
            return bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    fun toBitmap(source: VideoFrame.I420Buffer, target: Bitmap) {
        require(target.width == source.width && target.height == source.height && target.isMutable)
        PrivacyNativePixels.i420ToBitmap(source.dataY.slice(), source.strideY,
            source.dataU.slice(), source.strideU, source.dataV.slice(), source.strideV, target)
    }

    fun toI420(bitmap: Bitmap): VideoFrame.I420Buffer {
        require(bitmap.config == Bitmap.Config.ARGB_8888)
        val size = bitmap.rowBytes * bitmap.height
        val buffer = rgba?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(size).also { rgba = it }
        buffer.clear()
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        val output = outputPool.acquire(bitmap.width, bitmap.height)
        try {
            // Android RGBA byte order corresponds to libyuv ABGR on little-endian Android.
            YuvHelper.ABGRToI420(buffer, bitmap.rowBytes, output.dataY, output.strideY,
                output.dataU, output.strideU, output.dataV, output.strideV, bitmap.width, bitmap.height)
            return output
        } catch (error: Throwable) { output.release(); throw error }
    }

    override fun close() {outputPool.close();rgba=null}
}

internal object PrivacyNativePixels {
    init { System.loadLibrary("innolive_privacy") }
    external fun i420ToBitmap(y: ByteBuffer, yStride: Int, u: ByteBuffer, uStride: Int,
                             v: ByteBuffer, vStride: Int, bitmap: Bitmap)
    external fun bitmapToTensor(bitmap: Bitmap, buffer: ByteBuffer)
    external fun bitmapToBgrTensor(bitmap: Bitmap, buffer: ByteBuffer, width: Int, height: Int)
    external fun bitmapToFaceTensor(bitmap: Bitmap, buffer: ByteBuffer)
    external fun copyPlane(source: ByteBuffer, rowStride: Int, pixelStride: Int,
                           width: Int, height: Int, target: ByteBuffer, targetStride: Int)
    external fun neuralAcceleratorCount(): Int
    external fun finiteFloats(values: FloatArray): Boolean
    external fun cameraChromaLayout(u:ByteBuffer,v:ByteBuffer,uStride:Int,vStride:Int,width:Int,height:Int):Int
    external fun computeInstanceMasks(coefficients: FloatArray, bounds: IntArray, prototypes: FloatArray,
                                     prototypesValidated: Boolean = false): Array<ByteArray>
    external fun composite(source: Bitmap, blurred: Bitmap, alpha: ByteArray, left: Int, top: Int,
                           resizedWidth: Int, resizedHeight: Int, output: Bitmap)
}
