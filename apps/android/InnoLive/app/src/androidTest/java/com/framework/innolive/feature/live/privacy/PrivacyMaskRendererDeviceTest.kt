package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyMaskRendererDeviceTest {
    @SdkSuppress(minSdkVersion = 31)
    @Test fun gpuBlurSurvivesRepeatedFramesAndGeometryChanges() {
        PrivacyBitmapBlur().use { blur ->
            for ((width, height) in listOf(384 to 192, 384 to 192, 192 to 384, 1 to 1)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(IntArray(width * height) { if (it % width < width / 2) Color.BLACK else Color.WHITE },
                    0, width, 0, 0, width, height)
                val output = PrivacyMaskRenderer.render(bitmap, ByteArray(160 * 160) { -1 },
                    PrivacySegmentation.Letterbox(width, height), blur::apply)
                try {
                    assertTrue("GPU path must execute, not silently fall back", blur.lastUsedGpu)
                    assertEquals(width, output.width)
                    assertEquals(height, output.height)
                    if (width > 1) assertTrue(Color.red(output.getPixel(width / 2 - 6, height / 2)) in 1..254)
                    else assertEquals(Color.WHITE, output.getPixel(0, 0))
                } finally { output.recycle(); bitmap.recycle() }
            }
        }
    }
    @Test fun protectedPixelsContainGaussianBlurAfterPixelation() {
        val bitmap = Bitmap.createBitmap(384, 192, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(384 * 192) { if (it % 384 < 192) Color.BLACK else Color.WHITE },
            0, 384, 0, 0, 384, 192)
        val small = Bitmap.createScaledBitmap(bitmap, 16, 8, true)
        val pixelated = Bitmap.createScaledBitmap(small, 384, 192, false)
        try { assertEquals(Color.BLACK, pixelated.getPixel(168, 96)) }
        finally { pixelated.recycle(); small.recycle() }
        val output = PrivacyMaskRenderer.render(bitmap, ByteArray(160 * 160) { -1 },
            PrivacySegmentation.Letterbox(384, 192))
        try {
            // Pure pixelation leaves x=168 black; Gaussian must soften the block boundary.
            assertTrue(Color.red(output.getPixel(168, 96)) in 1..127)
            assertTrue(Color.red(output.getPixel(216, 96)) in 128..254)
            assertEquals(Color.BLACK, output.getPixel(0, 96))
            assertEquals(Color.WHITE, output.getPixel(383, 96))
        } finally { output.recycle(); bitmap.recycle() }
    }

    @Test fun coreRemainsOpaqueWhileOuterMaskIsFeathered() {
        val mask = ByteArray(160 * 160).also { it[80 * 160 + 80] = -1 }
        val alpha = PrivacyMaskRenderer.featheredMask(mask)
        for (y in 78..82) for (x in 78..82) assertEquals(255, alpha[y * 160 + x].toInt() and 255)
        assertTrue((alpha[80 * 160 + 85].toInt() and 255) in 1..254)
        assertEquals(0, alpha[20 * 160 + 20].toInt())
    }

    @Test fun tinyAndEmptyMasksDoNotShareOrRecycleCapturePixels() {
        val original = Bitmap.createBitmap(intArrayOf(Color.MAGENTA), 1, 1, Bitmap.Config.ARGB_8888)
        try {
            for (mask in listOf(ByteArray(160 * 160), ByteArray(160 * 160) { -1 })) {
                val output = PrivacyMaskRenderer.render(original, mask, PrivacySegmentation.Letterbox(1, 1))
                assertNotSame(original, output)
                output.recycle()
                assertFalse(original.isRecycled)
                assertEquals(Color.MAGENTA, original.getPixel(0, 0))
            }
        } finally { original.recycle() }
    }
}
