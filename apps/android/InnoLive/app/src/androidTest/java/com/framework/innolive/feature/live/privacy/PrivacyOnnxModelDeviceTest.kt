package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

@RunWith(AndroidJUnit4::class)
class PrivacyOnnxModelDeviceTest {
    @Test fun reusedScratchPreservesEarlierFramesAcrossRotationsAndGeometryChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        PrivacyFrameProcessor(context).use { processor ->
            var earlier: VideoFrame? = null
            try {
                for ((sample, rotation) in listOf(0, 90, 180, 270, 0).withIndex()) {
                    val width = if (sample == 4) 48 else 64
                    val height = if (sample == 4) 32 else 48
                    val buffer = JavaI420Buffer.allocate(width, height)
                    repeat(buffer.dataY.capacity()) { buffer.dataY.put(it, (if (sample == 0) 40 else 180).toByte()) }
                    repeat(buffer.dataU.capacity()) { buffer.dataU.put(it, 128.toByte()) }
                    repeat(buffer.dataV.capacity()) { buffer.dataV.put(it, 128.toByte()) }
                    val frame = VideoFrame(buffer, rotation, 100L + sample)
                    try {
                        val output = processor.process(frame)
                        assertEquals(width, output.buffer.width); assertEquals(height, output.buffer.height)
                        assertEquals(rotation, output.rotation); assertEquals(100L + sample, output.timestampNs)
                        val plane = output.buffer.toI420()!!
                        try { assertTrue(kotlin.math.abs((if (sample == 0) 40 else 180) - (plane.dataY.get(0).toInt() and 255)) <= 2) }
                        finally { plane.release() }
                        if (sample == 0) earlier = output else output.release()
                        earlier!!.buffer.toI420()!!.let { held ->
                            try { assertTrue(kotlin.math.abs(40 - (held.dataY.get(0).toInt() and 255)) <= 2) }
                            finally { held.release() }
                        }
                    } finally { frame.release() }
                }
            } finally { earlier?.release() }
        }
    }
    @Test fun pinnedModelLoadsAndProcessesAnAndroidBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70, 120, 160))
        }
        PrivacyOnnxModel(context).use { model ->
            val result = model.process(bitmap)
            try {
                assertEquals(bitmap.width, result.width)
                assertEquals(bitmap.height, result.height)
            } finally {
                result.recycle()
            }
        }
        bitmap.recycle()
    }

    @Test fun processedFramePreservesSensorGeometryRotationAndTimestamp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val buffer = JavaI420Buffer.allocate(64, 48)
        buffer.dataY.clear()
        repeat(buffer.strideY * 48) { buffer.dataY.put(96.toByte()) }
        buffer.dataU.clear()
        repeat(buffer.strideU * 24) { buffer.dataU.put(128.toByte()) }
        buffer.dataV.clear()
        repeat(buffer.strideV * 24) { buffer.dataV.put(128.toByte()) }
        val source = VideoFrame(buffer, 90, 123_456L)
        try {
            PrivacyFrameProcessor(context).use { processor ->
                val result = processor.process(source)
                try {
                    assertEquals(64, result.buffer.width)
                    assertEquals(48, result.buffer.height)
                    assertEquals(90, result.rotation)
                    assertEquals(123_456L, result.timestampNs)
                } finally {
                    result.release()
                }
            }
        } finally {
            source.release()
        }
    }

    @Test fun protectedMaskChangesOnlyTheProtectedImageRegion() {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(320 * 320) { index ->
            if (index % 320 % 2 == 0) Color.BLACK else Color.WHITE
        }
        bitmap.setPixels(pixels, 0, 320, 0, 0, 320, 320)
        val mask = ByteArray(160 * 160).also { it[80 * 160 + 80] = -1 }
        val result = PrivacyMaskRenderer.render(bitmap, mask, PrivacySegmentation.Letterbox(320, 320))
        try {
            assertNotEquals(bitmap.getPixel(160, 160), result.getPixel(160, 160))
            assertEquals(bitmap.getPixel(20, 20), result.getPixel(20, 20))
        } finally {
            result.recycle()
            bitmap.recycle()
        }
    }

    @Test fun pinnedFaceRecognizerProducesNormalizedEmbedding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(102, 128, 153))
        }
        try {
            PrivacyFaceModel(context).use { model ->
                val result = model.predict(bitmap, floatArrayOf(
                    .34f, .46f, .66f, .46f, .50f, .64f, .37f, .82f, .63f, .82f,
                ))
                assertEquals(512, result.size)
                assertTrue(result.all(Float::isFinite))
                assertEquals(1f, result.sumOf { (it * it).toDouble() }.toFloat(), .001f)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun repeatedProtectedFramesKeepProducingOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70, 120, 160))
        }
        try {
            PrivacyOnnxModel(context).use { model ->
                repeat(5) {
                    val output = model.process(bitmap)
                    assertEquals(640, output.width)
                    output.recycle()
                }
            }
        } finally { bitmap.recycle() }
    }
}
