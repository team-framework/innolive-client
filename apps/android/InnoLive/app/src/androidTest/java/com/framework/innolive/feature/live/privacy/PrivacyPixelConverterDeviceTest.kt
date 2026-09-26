package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import org.webrtc.JavaI420Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PrivacyPixelConverterDeviceTest {
    @Test fun cameraPlaneCopyMatchesReferenceForStridesOffsetsAndTruncatedLastRow() {
        for (pixelStride in listOf(1, 2, 3)) for (width in listOf(1, 17, 33, 960)) {
            val height = if (width == 960) 540 else 3
            val rowStride = width * pixelStride + 7
            val prefix = 5
            val required = (height - 1) * rowStride + (width - 1) * pixelStride + 1
            val source = ByteBuffer.allocateDirect(prefix + required).apply {
                repeat(capacity()) { put(it, (it * 13).toByte()) }
                position(prefix)
            }
            val output = ByteBuffer.allocateDirect((width + 4) * height)
            val expected = ByteBuffer.allocateDirect(output.capacity())
            val cpuStarted = System.nanoTime()
            for (y in 0 until height) for (x in 0 until width) {
                expected.put(y * (width + 4) + x, source.get(prefix + y * rowStride + x * pixelStride))
            }
            val cpuMs = (System.nanoTime() - cpuStarted) / 1e6
            val nativeStarted = System.nanoTime()
            PrivacyNativePixels.copyPlane(source.slice(), rowStride, pixelStride, width, height, output, width + 4)
            val nativeMs = (System.nanoTime() - nativeStarted) / 1e6
            for (i in 0 until output.capacity()) assertEquals("stride=$pixelStride index=$i", expected.get(i), output.get(i))
            assertEquals(prefix, source.position())
            if (width == 960) Log.i("PrivacyPerformance", "camera_plane stride=$pixelStride reference_ms=$cpuMs native_ms=$nativeMs")
            assertThrows(IllegalArgumentException::class.java) {
                PrivacyNativePixels.copyPlane(source.slice().apply { limit(required - 1) }.slice(), rowStride,
                    pixelStride, width, height, output, width + 4)
            }
        }
    }

    @Before fun initializeWebRtc() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(
            InstrumentationRegistry.getInstrumentation().targetContext).createInitializationOptions())
    }

    @Test fun nativeInputMatchesReferenceForOddSizeAndPaddedPlanes() {
        val y = ByteBuffer.allocateDirect(24 * 3)
        val u = ByteBuffer.allocateDirect(15 * 2)
        val v = ByteBuffer.allocateDirect(16 * 2)
        repeat(y.capacity()) { y.put(it, (16 + it * 9).toByte()) }
        repeat(u.capacity()) { u.put(it, (80 + it * 7).toByte()) }
        repeat(v.capacity()) { v.put(it, (100 + it * 9).toByte()) }
        val input = JavaI420Buffer.wrap(21, 3, y, 24, u, 15, v, 16, null)
        try {
            val bitmap = PrivacyPixelConverter().toBitmap(input)
            try {
                for (row in 0..2) for (col in 0..20) {
                    val c = 298 * ((y.get(row * 24 + col).toInt() and 255) - 16).coerceAtLeast(0)
                    val cu = (u.get(row / 2 * 15 + col / 2).toInt() and 255) - 128
                    val cv = (v.get(row / 2 * 16 + col / 2).toInt() and 255) - 128
                    val expected = Color.rgb(((c + 409 * cv + 128) shr 8).coerceIn(0, 255),
                        ((c - 100 * cu - 208 * cv + 128) shr 8).coerceIn(0, 255),
                        ((c + 516 * cu + 128) shr 8).coerceIn(0, 255))
                    assertEquals(expected, bitmap.getPixel(col, row))
                }
            } finally { bitmap.recycle() }
        } finally { input.release() }
    }

    @Test fun nativeOutputKeepsRedBlueOrderAndLimitedRangeWithScratchReuse() {
        val converter = PrivacyPixelConverter()
        for ((color, expected) in listOf(Color.RED to intArrayOf(82, 90, 240),
            Color.BLUE to intArrayOf(41, 240, 110), Color.GREEN to intArrayOf(144, 54, 34))) {
            for ((width, height) in listOf(9 to 5, 2 to 2)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try {
                    val output = converter.toI420(bitmap)
                    try {
                        for ((plane, value) in listOf(output.dataY to expected[0], output.dataU to expected[1], output.dataV to expected[2])) {
                            assertTrue("color=$color expected=$value actual=${plane.get(0).toInt() and 255}",
                                kotlin.math.abs((plane.get(0).toInt() and 255) - value) <= 2)
                        }
                        assertEquals(width, output.width); assertEquals(height, output.height)
                    } finally { output.release() }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun reusedNativeTensorUsesRgbChannelsAndRejectsNonFinitePrototypes() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val bytes = ByteBuffer.allocateDirect(2 * 3 * 4).order(ByteOrder.nativeOrder())
        try {
            bitmap.setPixel(0, 0, Color.RED); bitmap.setPixel(1, 0, Color.BLUE)
            PrivacyNativePixels.bitmapToTensor(bitmap, bytes)
            val floats = bytes.asFloatBuffer()
            assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f), FloatArray(6).also(floats::get), 0f)
            bitmap.eraseColor(Color.GREEN)
            PrivacyNativePixels.bitmapToTensor(bitmap, bytes)
            floats.rewind()
            assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f), FloatArray(6).also(floats::get), 0f)
            assertTrue(PrivacyNativePixels.finiteFloats(floatArrayOf(0f, -1f, Float.MAX_VALUE)))
            assertFalse(PrivacyNativePixels.finiteFloats(floatArrayOf(Float.NaN)))
            assertFalse(PrivacyNativePixels.finiteFloats(floatArrayOf(Float.POSITIVE_INFINITY)))
            assertThrows(IllegalArgumentException::class.java) {
                val prototypes = FloatArray(32 * 160 * 160).also { it[it.lastIndex] = Float.NaN }
                PrivacySegmentation.unionMask(emptyList(), prototypes, PrivacyNativePixels::finiteFloats)
            }
            assertThrows(IllegalArgumentException::class.java) {
                PrivacyNativePixels.bitmapToTensor(bitmap, ByteBuffer.allocateDirect(4))
            }
            assertThrows(IllegalArgumentException::class.java) {
                PrivacyNativePixels.i420ToBitmap(ByteBuffer.allocateDirect(1), 2,
                    ByteBuffer.allocateDirect(1), 1, ByteBuffer.allocateDirect(1), 1, bitmap)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun nativeCompositeMatchesOpaqueAndFeatheredReferenceWithLetterbox() {
        val source = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        val blurred = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val output = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        val layout = PrivacySegmentation.Letterbox(7, 5)
        val alpha = ByteArray(160 * 160) { when (it % 3) { 0 -> 0; 1 -> 127; else -> -1 } }
        try {
            for (y in 0 until 5) for (x in 0 until 7) source.setPixel(x, y, Color.rgb(x * 40, y * 50, 170))
            PrivacyNativePixels.composite(source, blurred, alpha, layout.left, layout.top,
                layout.resizedWidth, layout.resizedHeight, output)
            for (y in 0 until 5) for (x in 0 until 7) {
                val mx = (layout.left + x * layout.resizedWidth / 7) / 4
                val my = (layout.top + y * layout.resizedHeight / 5) / 4
                val coverage = alpha[my * 160 + mx].toInt() and 255
                val original = source.getPixel(x, y)
                fun blend(channel: (Int) -> Int): Int =
                    (channel(original) * (255 - coverage) + channel(Color.CYAN) * coverage + 127) / 255
                assertEquals(Color.rgb(blend(Color::red), blend(Color::green), blend(Color::blue)), output.getPixel(x, y))
            }
        } finally { source.recycle(); blurred.recycle(); output.recycle() }
    }

    @Test fun nativeCompositeCopiesUniformMaskRowsWithoutChangingInput() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blurred = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val layout = PrivacySegmentation.Letterbox(8, 8)
        try {
            val alpha = ByteArray(160 * 160) { index -> if (index / 160 < 80) -1 else 0 }
            PrivacyNativePixels.composite(source, blurred, alpha, layout.left, layout.top,
                layout.resizedWidth, layout.resizedHeight, output)
            for (y in 0 until 8) for (x in 0 until 8) {
                assertEquals(if (y < 4) Color.CYAN else Color.RED, output.getPixel(x, y))
                assertEquals(Color.RED, source.getPixel(x, y))
            }
        } finally { source.recycle(); blurred.recycle(); output.recycle() }
    }

    @Test fun scratchRotationKeepsAsymmetricPixelPositionsInEveryDirection() {
        val source = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 5) for (x in 0 until 7) source.setPixel(x, y, Color.rgb(x * 40, y * 50, 170))
            for (rotation in listOf(90, 180, 270)) {
                val output = Bitmap.createBitmap(if (rotation == 180) 7 else 5,
                    if (rotation == 180) 5 else 7, Bitmap.Config.ARGB_8888)
                try {
                    PrivacyBitmapRotation.draw(source, rotation, output)
                    for (y in 0 until 5) for (x in 0 until 7) {
                        val (dx, dy) = when (rotation) { 90 -> 4 - y to x; 180 -> 6 - x to 4 - y; else -> y to 6 - x }
                        assertEquals(source.getPixel(x, y), output.getPixel(dx, dy))
                    }
                } finally { output.recycle() }
            }
        } finally { source.recycle() }
    }
}
