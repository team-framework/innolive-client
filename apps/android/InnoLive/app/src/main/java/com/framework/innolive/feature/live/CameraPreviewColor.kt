package com.framework.innolive.feature.live

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.os.Build
import android.view.View

/** Approximate the sender's YUV chroma adjustment on CameraX's RGB TextureView. */
internal fun cameraPreviewColorMatrix(settings: BroadcastVideoQualitySettings): FloatArray {
    val look = settings.normalized()
    val saturation = look.saturation
    val gray = 1f - saturation
    val uOffset = -VIDEO_WARMTH_CHROMA_OFFSET * look.warmth * saturation
    val vOffset = VIDEO_WARMTH_CHROMA_OFFSET * look.warmth * saturation
    return floatArrayOf(
        saturation + gray * 0.299f, gray * 0.587f, gray * 0.114f, 0f, 409f / 256f * vOffset,
        gray * 0.299f, saturation + gray * 0.587f, gray * 0.114f, 0f,
            (-100f * uOffset - 208f * vOffset) / 256f,
        gray * 0.299f, gray * 0.587f, saturation + gray * 0.114f, 0f, 516f / 256f * uOffset,
        0f, 0f, 0f, 1f, 0f,
    )
}

internal fun applyCameraPreviewColor(view: View, settings: BroadcastVideoQualitySettings?) {
    val normalized = settings?.normalized()
    val filter = if (normalized == null || (normalized.warmth == 0f && normalized.saturation == 1f)) {
        null
    } else {
        ColorMatrixColorFilter(cameraPreviewColorMatrix(normalized))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.setRenderEffect(filter?.let { RenderEffect.createColorFilterEffect(it) })
    } else {
        view.setLayerType(
            if (filter == null) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE,
            filter?.let { Paint().apply { colorFilter = it } },
        )
    }
}
