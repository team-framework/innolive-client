package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object PrivacyMaskRenderer {
    /** Opaque cells added around the face contour, like the server's one-mask-pixel pad plus 8px
     * margin. One 160-grid cell is 8–12 output px at 720p–1080p; the 3x3 square equals a disk of
     * radius sqrt(2), so the margin follows the contour instead of growing into a box. */
    const val PROTECTED_EDGE_CELLS = 1
    /** 영상 조절 가림 영역 너비의 상한입니다. 너비 64px가 854px 폭 영상에서 12칸입니다. */
    const val MAX_EDGE_CELLS = 12

    /** 송출 영상 px로 받은 가림 영역 너비를 160 마스크 칸 수로 바꿉니다. 1칸은 영상 긴 변의 1/160이며,
     * 서버 기본 8px는 720p·1080p 모두 지금과 같은 1칸입니다. 0px여도 1칸 아래로 줄이지 않습니다. */
    fun edgeCells(width: Int, height: Int, marginPx: Int): Int {
        require(width > 0 && height > 0)
        val cellPx = max(width, height).toDouble() / PrivacySegmentation.MASK_SIZE
        return (marginPx / cellPx).roundToInt().coerceIn(PROTECTED_EDGE_CELLS, MAX_EDGE_CELLS)
    }

    /** The inner mask may only grow. */
    fun expandedMask(mask: ByteArray, radius: Int = PROTECTED_EDGE_CELLS): ByteArray {
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
               filter: (Bitmap) -> Bitmap = { broadcastBlur(it) },
               edgeCells: Int = PROTECTED_EDGE_CELLS): Bitmap {
        require(upright.width == layout.sourceWidth && upright.height == layout.sourceHeight)
        require(mask.size == PrivacySegmentation.MASK_SIZE * PrivacySegmentation.MASK_SIZE)
        val width = upright.width
        val height = upright.height
        // Never return/recycle the capture bitmap, even for a 1x1 input or an empty mask.
        if (mask.all { it.toInt() == 0 }) return checkNotNull(upright.copy(Bitmap.Config.ARGB_8888, false))
        val alpha = expandedMask(mask, edgeCells)
        val mosaic = filter(upright)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PrivacyNativePixels.composite(upright, mosaic, alpha, layout.left, layout.top,
                layout.resizedWidth, layout.resizedHeight, output)
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
        finally { mosaic.recycle() }
    }

    /** iOS broadcastStyleBlur on the CPU: area-average reduction to 1/4, a Gaussian with the
     * reduced sigma, then scaling back. No pixelation grid: a point-sampled block grid shimmers
     * between frames. The mask remains at its original resolution; this only reduces filter work. */
    internal fun broadcastBlur(upright: Bitmap,
                               sigma: Int = PrivacyBroadcastBlur.sigma(upright.width, upright.height)): Bitmap {
        val reduced = reduce(upright)
        try {
            val pixels = IntArray(reduced.width * reduced.height)
            reduced.getPixels(pixels, 0, reduced.width, 0, 0, reduced.width, reduced.height)
            val blurred = PrivacyGaussianBlur.apply(pixels, reduced.width, reduced.height,
                PrivacyBroadcastBlur.reducedSigma(sigma))
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
