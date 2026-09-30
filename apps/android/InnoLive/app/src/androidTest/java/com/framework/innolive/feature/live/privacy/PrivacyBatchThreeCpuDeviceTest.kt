package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.YuvHelper
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PrivacyBatchThreeCpuDeviceTest {
    @Test fun cpuFallbackScratchAndOutputPoolMatchOriginalPixels() {
        val width=320;val height=180
        val pixels=IntArray(width*height) {index ->
            0xff000000.toInt() or ((index*7%256) shl 16) or ((index*11%256) shl 8) or (index*13%256)
        }
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels,0,width,0,0,width,height)
        val rgba=ByteBuffer.allocateDirect(bitmap.rowBytes*height)
        fun oldI420():VideoFrame.I420Buffer {
            rgba.clear();bitmap.copyPixelsToBuffer(rgba);rgba.rewind()
            val out=JavaI420Buffer.allocate(width,height)
            YuvHelper.ABGRToI420(rgba,bitmap.rowBytes,out.dataY,out.strideY,out.dataU,out.strideU,
                out.dataV,out.strideV,width,height)
            return out
        }
        fun oldBlur():IntArray {
            val sigma=6.0;val radius=ceil(3*sigma).toInt()
            val weights=DoubleArray(radius*2+1) {i ->
                val offset=i-radius
                exp(-offset.toDouble()*offset/(2*sigma*sigma))
            }
            val sum=weights.sum()
            val kernel=IntArray(weights.size) {(weights[it]/sum*65536).roundToInt()}
            kernel[radius]+=65536-kernel.sum()
            val horizontal=IntArray(pixels.size);val output=IntArray(pixels.size)
            for(vertical in listOf(false,true)) {
                val source=if(vertical)horizontal else pixels
                val target=if(vertical)output else horizontal
                for(y in 0 until height)for(x in 0 until width) {
                    var alpha=0;var red=0;var green=0;var blue=0
                    for(index in kernel.indices) {
                        val offset=index-radius
                        val position=if(vertical)(y+offset).coerceIn(0,height-1)*width+x
                            else y*width+(x+offset).coerceIn(0,width-1)
                        val pixel=source[position];val weight=kernel[index]
                        alpha+=(pixel ushr 24)*weight
                        red+=((pixel ushr 16) and 255)*weight
                        green+=((pixel ushr 8) and 255)*weight
                        blue+=(pixel and 255)*weight
                    }
                    target[y*width+x]=(((alpha+32768) ushr 16) shl 24) or
                        (((red+32768) ushr 16) shl 16) or
                        (((green+32768) ushr 16) shl 8) or ((blue+32768) ushr 16)
                }
            }
            return output
        }
        fun percentile(values:List<Double>,fraction:Double):Double =
            values.sorted()[(values.size*fraction).toInt().coerceAtMost(values.lastIndex)]
        try {
            val baselineBlur=ArrayList<Double>();val optimizedBlur=ArrayList<Double>()
            repeat(25) {sample ->
                val aStart=System.nanoTime();val a=oldBlur();val aMs=(System.nanoTime()-aStart)/1e6
                val bStart=System.nanoTime();val b=PrivacyGaussianBlur.apply(pixels,width,height,6.0)
                val bMs=(System.nanoTime()-bStart)/1e6
                assertArrayEquals(a,b)
                if(sample>=5) {baselineBlur.add(aMs);optimizedBlur.add(bMs)}
            }
            Log.i("PrivacyBatch","batch=3 stage=cpu_blur baseline_p50_ms=${percentile(baselineBlur,.5)} optimized_p50_ms=${percentile(optimizedBlur,.5)} baseline_p95_ms=${percentile(baselineBlur,.95)} optimized_p95_ms=${percentile(optimizedBlur,.95)}")
            PrivacyPixelConverter().use {converter ->
                val baseline=ArrayList<Double>();val optimized=ArrayList<Double>()
                repeat(60) {sample ->
                    val aStart=System.nanoTime();val a=oldI420();val aMs=(System.nanoTime()-aStart)/1e6
                    val bStart=System.nanoTime();val b=converter.toI420(bitmap);val bMs=(System.nanoTime()-bStart)/1e6
                    try {
                        for((left,right) in listOf(a.dataY to b.dataY,a.dataU to b.dataU,a.dataV to b.dataV))
                            for(i in 0 until left.remaining()) assertEquals(left.get(i),right.get(i))
                    } finally {a.release();b.release()}
                    if(sample>=10) {baseline.add(aMs);optimized.add(bMs)}
                }
                Log.i("PrivacyBatch","batch=3 stage=cpu_i420 baseline_p50_ms=${percentile(baseline,.5)} optimized_p50_ms=${percentile(optimized,.5)} baseline_p95_ms=${percentile(baseline,.95)} optimized_p95_ms=${percentile(optimized,.95)}")
            }
        } finally {bitmap.recycle()}
    }
}
