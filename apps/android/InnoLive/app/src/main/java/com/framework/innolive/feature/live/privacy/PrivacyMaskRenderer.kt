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
        val margin = min(radius, size - 1)
        val horizontal = ByteArray(mask.size)
        val expanded = ByteArray(mask.size)
        for (y in 0 until size) {
            var count = (0..margin).count { mask[y * size + it].toInt() != 0 }
            for (x in 0 until size) {
                if (count > 0) horizontal[y * size + x] = -1
                if (x - margin >= 0 && mask[y * size + x - margin].toInt() != 0) count--
                if (x + margin + 1 < size && mask[y * size + x + margin + 1].toInt() != 0) count++
            }
        }
        for (x in 0 until size) {
            var count = (0..margin).count { horizontal[it * size + x].toInt() != 0 }
            for (y in 0 until size) {
                if (count > 0) expanded[y * size + x] = -1
                if (y - margin >= 0 && horizontal[(y - margin) * size + x].toInt() != 0) count--
                if (y + margin + 1 < size && horizontal[(y + margin + 1) * size + x].toInt() != 0) count++
            }
        }
        return expanded
    }

    fun render(upright: Bitmap, mask: ByteArray, layout: PrivacySegmentation.Letterbox,
               filter: (Bitmap) -> Bitmap = ::broadcastBlur): Bitmap {
        require(upright.width == layout.sourceWidth && upright.height == layout.sourceHeight)
        require(mask.size == PrivacySegmentation.MASK_SIZE * PrivacySegmentation.MASK_SIZE)
        val width = upright.width
        val height = upright.height
        // Never return/recycle the capture bitmap, even for a 1x1 input or an empty mask.
        if (mask.all { it.toInt() == 0 }) return checkNotNull(upright.copy(Bitmap.Config.ARGB_8888, false))
        val alpha = featheredMask(mask)
        val mosaic = filter(upright)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PrivacyNativePixels.composite(upright, mosaic, alpha, layout.left, layout.top,
                layout.resizedWidth, layout.resizedHeight, output)
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
        finally { mosaic.recycle() }
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

    /** iOS broadcastStyleBlur on the CPU: area-average reduction to 1/4, a Gaussian with the
     * reduced sigma, then scaling back. No pixelation grid: a point-sampled block grid shimmers
     * between frames. The mask remains at its original resolution; this only reduces filter work. */
    internal fun broadcastBlur(upright: Bitmap): Bitmap {
        val reduced = reduce(upright)
        try {
            val pixels = IntArray(reduced.width * reduced.height)
            reduced.getPixels(pixels, 0, reduced.width, 0, 0, reduced.width, reduced.height)
            val blurred = PrivacyGaussianBlur.apply(pixels, reduced.width, reduced.height,
                PrivacyBroadcastBlur.reducedSigma(upright.width, upright.height))
            reduced.setPixels(blurred, 0, reduced.width, 0, 0, reduced.width, reduced.height)
            val output = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(output).drawBitmap(reduced, null, Rect(0, 0, output.width, output.height), Paint(Paint.FILTER_BITMAP_FLAG))
                return output
            } catch (error: Throwable) { output.recycle(); throw error }
        } finally { reduced.recycle() }
    }

    /** Reduce to 1/4 with two bilinear half steps; each is an exact 2x2 average, so every source
     * pixel contributes. One 1/4 bilinear draw would sample only two of every four texels. */
    internal fun reduce(source: Bitmap): Bitmap {
        var current = source
        repeat(2) {
            val next = Bitmap.createBitmap(max(1, (current.width + 1) / 2), max(1, (current.height + 1) / 2),
                Bitmap.Config.ARGB_8888)
            try {
                Canvas(next).drawBitmap(current, null, Rect(0, 0, next.width, next.height), Paint(Paint.FILTER_BITMAP_FLAG))
            } catch (error: Throwable) { next.recycle(); throw error }
            finally { if (current !== source) current.recycle() }
            current = next
        }
        check(current.width == PrivacyBroadcastBlur.reducedSize(source.width) &&
            current.height == PrivacyBroadcastBlur.reducedSize(source.height))
        return current
    }
}
