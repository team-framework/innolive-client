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
    /**
     * 얼굴 윤곽 주변 최소 마스크 셀 수이며, 서버의 마스크 1픽셀 패딩과 8px 여백에 해당합니다.
     * 160 격자 한 셀은 720p–1080p에서 출력 8–12px입니다.
     */
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

    /**
     * 내부 마스크는 커지기만 합니다. 여백은 반경 r+0.5 셀의 원판이므로 넓은 여백도 사각형이 되지 않고
     * 얼굴 윤곽을 따라가며, 한 셀이면 같은 3x3 정사각형입니다.
     */
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

    /** 각 행 오프셋에서 원판의 가로 범위입니다. GPU 셰이더와 같이 dx² + dy² <= r² + r입니다. */
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
        // 1x1 입력이나 빈 마스크여도 캡처 비트맵을 반환하거나 재활용하지 않습니다.
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

    /** iOS `PrivacyMask.modelImage`와 같이 불투명 핵심부에 sigma 1.5 셀로 페더 처리한 더 넓은 마스크를 더합니다. */
    internal fun featheredMask(mask: ByteArray, edgeCells: Int = PROTECTED_EDGE_CELLS): ByteArray {
        val size = PrivacySegmentation.MASK_SIZE
        val coreCells = softCoreCells(edgeCells)
        val core = expandedMask(mask, coreCells)
        val outer = expandedMask(mask, coreCells + SOFT_OUTER_EXTRA_CELLS)
        val pixels = IntArray(mask.size) { if (outer[it].toInt() != 0) -1 else 0xff000000.toInt() }
        val blurred = PrivacyGaussianBlur.apply(pixels, size, size, SOFT_FEATHER_SIGMA)
        return ByteArray(mask.size) { if (core[it].toInt() != 0) -1 else (blurred[it] and 255).toByte() }
    }

    /**
     * CPU에서의 iOS broadcastStyleBlur입니다. 면적 평균으로 1/4까지 축소하고, 축소된 sigma로 가우시안을
     * 적용한 뒤 다시 확대합니다. 한 점에서 샘플링한 블록 격자는 프레임마다 반짝이므로 픽셀화 격자를 쓰지
     * 않습니다. 마스크는 원래 해상도를 유지하며, 이 과정은 필터 작업량만 줄입니다.
     */
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

    /**
     * bilinear 절반 축소를 두 번 해 1/4로 줄입니다. 각 단계는 정확한 2x2 평균이므로 모든 원본 픽셀이
     * 반영됩니다. 1/4 bilinear 그리기 한 번은 텍셀 네 개 중 두 개만 샘플링합니다.
     */
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
