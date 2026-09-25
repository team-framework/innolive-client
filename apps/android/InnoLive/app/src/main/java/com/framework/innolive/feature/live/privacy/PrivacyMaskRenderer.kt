package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
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

    fun render(upright: Bitmap, mask: ByteArray, layout: PrivacySegmentation.Letterbox): Bitmap {
        require(upright.width == layout.sourceWidth && upright.height == layout.sourceHeight)
        val protected = expandedMask(mask)
        val width = upright.width
        val height = upright.height
        val small = Bitmap.createScaledBitmap(upright, max(1, width / 24), max(1, height / 24), true)
        val mosaic = Bitmap.createScaledBitmap(small, width, height, false)
        small.recycle()
        try {
            val originalPixels = IntArray(width * height)
            val mosaicPixels = IntArray(width * height)
            upright.getPixels(originalPixels, 0, width, 0, 0, width, height)
            mosaic.getPixels(mosaicPixels, 0, width, 0, 0, width, height)
            for (y in 0 until height) {
                val modelY = layout.top + y * layout.resizedHeight / height
                val maskY = (modelY / 4).coerceIn(0, PrivacySegmentation.MASK_SIZE - 1)
                for (x in 0 until width) {
                    val modelX = layout.left + x * layout.resizedWidth / width
                    val maskX = (modelX / 4).coerceIn(0, PrivacySegmentation.MASK_SIZE - 1)
                    val index = y * width + x
                    if (protected[maskY * PrivacySegmentation.MASK_SIZE + maskX].toInt() != 0) {
                        originalPixels[index] = mosaicPixels[index]
                    }
                }
            }
            return Bitmap.createBitmap(originalPixels, width, height, Bitmap.Config.ARGB_8888)
        } finally {
            mosaic.recycle()
        }
    }
}
