package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceImageProcessorTest {
    @Test
    fun rejectsSourceWhoseShorterEdgeIs499Pixels() {
        val image = imageProxy(width = 700, height = 499)

        val result = image.toReferenceFaceBitmap()

        assertEquals(null, result)
        image.close()
    }

    @Test
    fun cropsTheRectangularSourceToTheCentralSquare() {
        val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.GREEN)
            drawRect(0f, 0f, 100f, 600f, Paint().apply { color = Color.RED })
            drawRect(700f, 0f, 800f, 600f, Paint().apply { color = Color.BLUE })
        }
        val image = imageProxy(source)

        val result = checkNotNull(image.toReferenceFaceBitmap())

        assertEquals(500, result.width)
        assertEquals(500, result.height)
        listOf(20, 250, 480).forEach { x ->
            val pixel = result.getPixel(x, 250)
            assertTrue(Color.green(pixel) > 220)
            assertTrue(Color.red(pixel) < 30)
            assertTrue(Color.blue(pixel) < 30)
        }
        result.recycle()
        image.close()
    }

    @Test
    fun appliesRotationBeforeCenterCrop() {
        val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.RED)
            drawRect(400f, 0f, 800f, 600f, Paint().apply { color = Color.BLUE })
        }
        val image = imageProxy(source, rotationDegrees = 90)

        val result = checkNotNull(image.toReferenceFaceBitmap())

        assertEquals(500, result.width)
        assertEquals(500, result.height)
        val top = result.getPixel(250, 60)
        val bottom = result.getPixel(250, 440)
        assertTrue(Color.red(top) > Color.blue(top))
        assertTrue(Color.blue(bottom) > Color.red(bottom))
        result.recycle()
        image.close()
    }

    @Test
    fun finalBitmapIs500SquareAndProducesDecodableJpeg() {
        val image = imageProxy(width = 640, height = 520)

        val result = checkNotNull(image.toReferenceFaceBitmap())
        val jpeg = result.toJpegBytes()
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

        assertNotNull(decoded)
        assertEquals(500, decoded.width)
        assertEquals(500, decoded.height)
        decoded.recycle()
        result.recycle()
        image.close()
    }

    private fun imageProxy(
        width: Int,
        height: Int,
        rotationDegrees: Int = 0,
    ): ImageProxy {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.GRAY)
        return imageProxy(bitmap, rotationDegrees)
    }

    private fun imageProxy(
        source: Bitmap,
        rotationDegrees: Int = 0,
    ): ImageProxy {
        val width = source.width
        val height = source.height
        val bytes = ByteArrayOutputStream().use { output ->
            check(source.compress(Bitmap.CompressFormat.JPEG, 100, output))
            output.toByteArray()
        }
        source.recycle()
        return FakeJpegImageProxy(bytes, width, height, rotationDegrees)
    }

    private class FakeJpegImageProxy(
        private val bytes: ByteArray,
        private val imageWidth: Int,
        private val imageHeight: Int,
        rotationDegrees: Int,
    ) : ImageProxy {
        private var cropRect = Rect(0, 0, imageWidth, imageHeight)
        private val imageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

            override fun getTimestamp(): Long = 0L

            override fun getRotationDegrees(): Int = rotationDegrees

            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }
        private val plane = object : ImageProxy.PlaneProxy {
            override fun getRowStride(): Int = bytes.size

            override fun getPixelStride(): Int = 1

            override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        }

        override fun close() = Unit

        override fun getCropRect(): Rect = cropRect

        override fun setCropRect(rect: Rect?) {
            cropRect = rect ?: Rect(0, 0, imageWidth, imageHeight)
        }

        override fun getFormat(): Int = ImageFormat.JPEG

        override fun getHeight(): Int = imageHeight

        override fun getWidth(): Int = imageWidth

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(plane)

        override fun getImageInfo(): ImageInfo = imageInfo

        override fun getImage(): Image? = null
    }
}
