package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyFaceCropDeviceTest {
    @Test fun fullFrameCropCanBeRecycledWithoutRecyclingImmutableSource() {
        assertIndependentCrop(PrivacySegmentation.Box(0f, 0f, 640f, 640f), 0, 64)
    }

    @Test fun partialCropKeepsTheExpectedPixelsAndIndependentLifetime() {
        assertIndependentCrop(PrivacySegmentation.Box(160f, 160f, 480f, 480f), 8, 48)
    }

    private fun assertIndependentCrop(box: PrivacySegmentation.Box, offset: Int, side: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pixels = IntArray(64 * 64) { Color.rgb(it % 64, it / 64, 120) }
        // This is the immutable RGB bitmap produced by the unrotated frame path.
        val source = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
        assertFalse(source.isMutable)
        try {
            val crop = checkNotNull(PrivacyFaceCoordinator(context).recognitionCrop(
                source, box, PrivacySegmentation.Letterbox(64, 64),
            ))
            try {
                assertNotSame(source, crop)
                assertEquals(side, crop.width)
                assertEquals(side, crop.height)
                val expected = IntArray(side * side) { pixels[(it / side + offset) * 64 + it % side + offset] }
                val actual = IntArray(side * side)
                crop.getPixels(actual, 0, side, 0, 0, side, side)
                assertArrayEquals(expected, actual)
            } finally { crop.recycle() }

            assertFalse(source.isRecycled)
            val remaining = IntArray(pixels.size)
            source.getPixels(remaining, 0, 64, 0, 0, 64, 64)
            assertArrayEquals(pixels, remaining)
        } finally { if (!source.isRecycled) source.recycle() }
    }
}
