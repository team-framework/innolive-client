package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object PrivacyMaskRenderer {
    /** Minimum mask cells around the face contour, like the server's one-mask-pixel pad plus 8px
     * margin. One 160-grid cell is 8–12 output px at 720p–1080p. */
    const val PROTECTED_EDGE_CELLS = 1
    /** 영상 조절 가림 영역 너비의 상한입니다. 너비 64px가 854px 폭 영상에서 12칸입니다. */
    const val MAX_EDGE_CELLS = 12
    /** iOS `PrivacyMask`: 2칸 불투명 core와, 2칸 더 넓힌 바깥 마스크를 sigma 1.5칸으로 흐린 값 중 큰 값입니다.
     * 너비를 넓히면 core가 그 칸 수를 따르고 바깥 마스크도 함께 넓어집니다. */
    const val SOFT_CORE_CELLS = 2
    const val SOFT_OUTER_EXTRA_CELLS = 2
    const val SOFT_FEATHER_SIGMA = 1.5
    /** GPU 팽창 셰이더의 고정 반복 범위입니다. 가장 넓은 너비의 바깥 마스크까지 덮습니다. */
    const val MAX_DILATE_CELLS = MAX_EDGE_CELLS + SOFT_OUTER_EXTRA_CELLS

    /** 불투명하게 남는 칸 수입니다. 영상 조절에서 고른 너비보다 좁아지지 않습니다. */
    fun softCoreCells(edgeCells: Int): Int = max(edgeCells, SOFT_CORE_CELLS)

    /** 송출 영상 px로 받은 가림 영역 너비를 160 마스크 칸 수로 바꿉니다. 1칸은 영상 긴 변의 1/160이며,
     * 서버 기본 8px는 720p·1080p 모두 지금과 같은 1칸입니다. 0px여도 1칸 아래로 줄이지 않습니다. */
    fun edgeCells(width: Int, height: Int, marginPx: Int): Int {
        require(width > 0 && height > 0)
        val cellPx = max(width, height).toDouble() / PrivacySegmentation.MASK_SIZE
        return (marginPx / cellPx).roundToInt().coerceIn(PROTECTED_EDGE_CELLS, MAX_EDGE_CELLS)
    }

    /** The inner mask may only grow. The margin is a disk of radius r+0.5 cells, so a wide margin
     * follows the face contour instead of turning into a box; one cell is the same 3x3 square. */
    fun expandedMask(mask: ByteArray, radius: Int = PROTECTED_EDGE_CELLS): ByteArray {
        val size = PrivacySegmentation.MASK_SIZE
        require(mask.size == size * size && radius >= 0)
        val margin = min(radius, size - 1)
        val halfWidths = diskHalfWidths(margin)
        val rows = halfWidths.distinct().associateWith { horizontalDilation(mask, size, it) }
        val expanded = ByteArray(mask.size)
        for (y in 0 until size) for (dy in -margin..margin) {
            val source = y + dy
            if (source !in 0 until size) continue
            val row = rows.getValue(halfWidths[abs(dy)])
            for (x in 0 until size) if (row[source * size + x].toInt() != 0) expanded[y * size + x] = -1
        }
        return expanded
    }

    /** Horizontal reach of the disk on each row offset: dx² + dy² <= r² + r, as in the GPU shader. */
    private fun diskHalfWidths(radius: Int): IntArray =
        IntArray(radius + 1) { dy -> floor(sqrt((radius * radius + radius - dy * dy).toDouble())).toInt() }

    private fun horizontalDilation(mask: ByteArray, size: Int, reach: Int): ByteArray {
        val output = ByteArray(mask.size)
        for (y in 0 until size) {
            var count = (0..reach).count { mask[y * size + it].toInt() != 0 }
            for (x in 0 until size) {
                if (count > 0) output[y * size + x] = -1
                if (x - reach >= 0 && mask[y * size + x - reach].toInt() != 0) count--
                if (x + reach + 1 < size && mask[y * size + x + reach + 1].toInt() != 0) count++
            }
        }
        return output
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
        val alpha = featheredMask(mask, edgeCells)
        val mosaic = filter(upright)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PrivacyNativePixels.composite(upright, mosaic, alpha, layout.left, layout.top,
                layout.resizedWidth, layout.resizedHeight, output)
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
        finally { mosaic.recycle() }
    }

    /** iOS `PrivacyMask.modelImage`: an opaque core plus a wider mask feathered at sigma 1.5 cells. */
    internal fun featheredMask(mask: ByteArray, edgeCells: Int = PROTECTED_EDGE_CELLS): ByteArray {
        val size = PrivacySegmentation.MASK_SIZE
        val coreCells = softCoreCells(edgeCells)
        val core = expandedMask(mask, coreCells)
        val outer = expandedMask(mask, coreCells + SOFT_OUTER_EXTRA_CELLS)
        val pixels = IntArray(mask.size) { if (outer[it].toInt() != 0) -1 else 0xff000000.toInt() }
        val blurred = PrivacyGaussianBlur.apply(pixels, size, size, SOFT_FEATHER_SIGMA)
        return ByteArray(mask.size) { if (core[it].toInt() != 0) -1 else (blurred[it] and 255).toByte() }
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
