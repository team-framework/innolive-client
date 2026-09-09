package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceFaceImageStoreTest {
    @Test
    fun storesByAccountAndReplacesPreviousFaceImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReferenceFaceImageStore(context)
        val firstImage = jpeg(0xFFFF0000.toInt())
        val secondImage = jpeg(0xFF0000FF.toInt())

        try {
            store.save("first@example.com", "face-1", firstImage)
            store.save("first@example.com", "face-2", secondImage)
            store.save("second@example.com", "face-1", firstImage)

            val reloadedStore = ReferenceFaceImageStore(context)
            assertNull(reloadedStore.load("first@example.com", "face-1"))
            assertDominantColor(
                checkNotNull(reloadedStore.load("first@example.com", "face-2")),
                expected = Color.BLUE,
            )
            assertDominantColor(
                checkNotNull(reloadedStore.load("second@example.com", "face-1")),
                expected = Color.RED,
            )

            reloadedStore.delete("first@example.com", "face-2")
            assertNull(reloadedStore.load("first@example.com", "face-2"))
            assertNotNull(reloadedStore.load("second@example.com", "face-1"))
        } finally {
            store.deleteAll("first@example.com")
            store.deleteAll("second@example.com")
        }
    }

    @Test
    fun appendsImagesWithoutRemovingExistingAccountImages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReferenceFaceImageStore(context)
        val firstImage = jpeg(0xFFFF0000.toInt())
        val secondImage = jpeg(0xFF0000FF.toInt())

        try {
            store.append("append@example.com", "face-1", firstImage)
            store.append("append@example.com", "face-2", secondImage)

            assertNotNull(store.load("append@example.com", "face-1"))
            assertNotNull(store.load("append@example.com", "face-2"))
        } finally {
            store.deleteAll("append@example.com")
        }
    }

    private fun jpeg(color: Int): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            compress(Bitmap.CompressFormat.JPEG, 90, output)
            recycle()
        }
        output.toByteArray()
    }

    private fun assertDominantColor(bitmap: Bitmap, expected: Int) {
        try {
            val pixel = bitmap.getPixel(0, 0)
            if (expected == Color.RED) {
                assertTrue(Color.red(pixel) > Color.blue(pixel))
            } else {
                assertTrue(Color.blue(pixel) > Color.red(pixel))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
