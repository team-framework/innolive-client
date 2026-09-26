package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

internal object PrivacyMaskRenderer {
    /** The inner mask may only grow, matching the iOS two-model-pixel protection margin. */
    fun expandedMask(mask: ByteArray, radius: Int = 2): ByteArray {
        val size = PrivacySegmentation.MASK_SIZE
        require(mask.size == size * size && radius >= 0)
        val expanded = ByteArray(mask.size)
        for (y in 0 until size) for (x in 0 until size) {
            if (mask[y * size + x].toInt() == 0) continue
            for (dy in max(0, y - radius)..min(size - 1, y + radius)) {
                for (dx in max(0, x - radius)..min(size - 1, x + radius)) {
                    expanded[dy * size + dx] = -1
                }
            }
        }
        return expanded
    }

    fun render(upright: Bitmap, mask: ByteArray, layout: PrivacySegmentation.Letterbox,
               filter: (Bitmap) -> Bitmap = ::pixelatedBlur): Bitmap {
        require(upright.width == layout.sourceWidth && upright.height == layout.sourceHeight)
        require(mask.size == PrivacySegmentation.MASK_SIZE * PrivacySegmentation.MASK_SIZE)
        val width = upright.width
        val height = upright.height
        // Never return/recycle the capture bitmap, even for a 1x1 input or an empty mask.
        if (mask.all { it.toInt() == 0 }) return checkNotNull(upright.copy(Bitmap.Config.ARGB_8888, false))
        val alpha = featheredMask(mask)
        val mosaic = filter(upright)
        try {
            val originalPixels = IntArray(width * height)
            val mosaicPixels = IntArray(width * height)
            upright.getPixels(originalPixels, 0, width, 0, 0, width, height)
            mosaic.getPixels(mosaicPixels, 0, width, 0, 0, width, height)
            val maskXs = IntArray(width) { x ->
                ((layout.left + x * layout.resizedWidth / width) / 4)
                    .coerceIn(0, PrivacySegmentation.MASK_SIZE - 1)
            }
            for (y in 0 until height) {
                val modelY = layout.top + y * layout.resizedHeight / height
                val maskY = (modelY / 4).coerceIn(0, PrivacySegmentation.MASK_SIZE - 1)
                for (x in 0 until width) {
                    val index = y * width + x
                    val coverage = alpha[maskY * PrivacySegmentation.MASK_SIZE + maskXs[x]].toInt() and 255
                    if (coverage == 255) originalPixels[index] = mosaicPixels[index]
                    else if (coverage > 0) {
                        val original = originalPixels[index]
                        val blurred = mosaicPixels[index]
                        fun blend(shift: Int): Int = ((((original ushr shift) and 255) * (255 - coverage) +
                            ((blurred ushr shift) and 255) * coverage + 127) / 255)
                        originalPixels[index] = (blend(24) shl 24) or (blend(16) shl 16) or
                            (blend(8) shl 8) or blend(0)
                    }
                }
            }
            return Bitmap.createBitmap(originalPixels, width, height, Bitmap.Config.ARGB_8888)
        } finally {
            mosaic.recycle()
        }
    }

    /** iOS: opaque 2px core + max(dilated 4px mask blurred at sigma 1.5, core). */
    internal fun featheredMask(mask: ByteArray): ByteArray {
        val core = expandedMask(mask)
        val outer = expandedMask(mask, 4)
        val pixels = IntArray(mask.size) { if (outer[it].toInt() != 0) -1 else 0xff000000.toInt() }
        val blurred = PrivacyGaussianBlur.apply(pixels, PrivacySegmentation.MASK_SIZE,
            PrivacySegmentation.MASK_SIZE, 1.5)
        return ByteArray(mask.size) { if (core[it].toInt() != 0) -1 else (blurred[it] and 255).toByte() }
    }

    /** Pixelate first, then Gaussian blur at quarter resolution (24px full-frame sigma).
     * The mask remains at its original resolution; this only reduces filter work. */
    internal fun pixelatedBlur(upright: Bitmap): Bitmap {
        val small = Bitmap.createBitmap(max(1, upright.width / 24), max(1, upright.height / 24), Bitmap.Config.ARGB_8888)
        val filtered = Bitmap.createBitmap(max(1, (upright.width + 3) / 4),
            max(1, (upright.height + 3) / 4), Bitmap.Config.ARGB_8888)
        try {
            Canvas(small).drawBitmap(upright, null, Rect(0, 0, small.width, small.height), Paint(Paint.FILTER_BITMAP_FLAG))
            Canvas(filtered).drawBitmap(small, null, Rect(0, 0, filtered.width, filtered.height), null)
            val pixels = IntArray(filtered.width * filtered.height)
            filtered.getPixels(pixels, 0, filtered.width, 0, 0, filtered.width, filtered.height)
            val blurred = PrivacyGaussianBlur.apply(pixels, filtered.width, filtered.height, 6.0)
            filtered.setPixels(blurred, 0, filtered.width, 0, 0, filtered.width, filtered.height)
            val output = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(output).drawBitmap(filtered, null, Rect(0, 0, output.width, output.height), Paint(Paint.FILTER_BITMAP_FLAG))
                return output
            } catch (error: Throwable) { output.recycle(); throw error }
        } finally { small.recycle(); filtered.recycle() }
    }
}
