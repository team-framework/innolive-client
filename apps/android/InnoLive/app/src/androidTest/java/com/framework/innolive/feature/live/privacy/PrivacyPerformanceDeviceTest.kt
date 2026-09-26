package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

/** Real models and production frame conversion; synthetic pixels never grant an identity exception. */
@RunWith(AndroidJUnit4::class)
class PrivacyPerformanceDeviceTest {
    @Test fun measureProcessingStagesAndRecognizer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions())
        PrivacyFrameProcessor(context).use { processor ->
            for ((width, height) in listOf(640 to 360, 1280 to 720, 1920 to 1080)) {
                val buffer = JavaI420Buffer.allocate(width, height)
                for (y in 0 until height) for (x in 0 until width) {
                    buffer.dataY.put(y * buffer.strideY + x, (32 + x % 180).toByte())
                }
                repeat(buffer.dataU.capacity()) { buffer.dataU.put(it, 128.toByte()) }
                repeat(buffer.dataV.capacity()) { buffer.dataV.put(it, 128.toByte()) }
                val frame = VideoFrame(buffer, 90, 123L)
                try {
                    repeat(4) { sample ->
                        val output = processor.process(frame)
                        try { assertEquals(width, output.buffer.width); assertEquals(height, output.buffer.height) }
                        finally { output.release() }
                        Log.i(TAG, "frame=${width}x$height sample=$sample ${processor.lastTimings}")
                    }
                } finally { frame.release() }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(IntArray(width * height) { if (it % width < width / 2) Color.BLACK else Color.WHITE },
                        0, width, 0, 0, width, height)
                }
                try { PrivacyBitmapBlur().use { blur ->
                    repeat(4) { sample ->
                        val started = System.nanoTime()
                        val output = PrivacyMaskRenderer.render(bitmap, ByteArray(160 * 160) { -1 },
                            PrivacySegmentation.Letterbox(width, height), blur::apply)
                        output.recycle()
                        Log.i(TAG, "protected_render=${width}x$height sample=$sample ms=${(System.nanoTime() - started) / 1e6}")
                    }
                } } finally { bitmap.recycle() }
            }
        }
        val image = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(102, 128, 153))
        }
        try {
            PrivacyFaceModel(context).use { model ->
                repeat(4) { sample ->
                    val started = System.nanoTime()
                    val embedding = model.predict(image, floatArrayOf(.34f, .46f, .66f, .46f,
                        .50f, .64f, .37f, .82f, .63f, .82f))
                    assertEquals(512, embedding.size)
                    Log.i(TAG, "recognizer sample=$sample ms=${(System.nanoTime() - started) / 1e6}")
                }
            }
        } finally { image.recycle() }
    }

    companion object { private const val TAG = "PrivacyPerformance" }
}
