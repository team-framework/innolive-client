package com.framework.innolive.feature.live

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

data class CameraResolution(
    val width: Int,
    val height: Int,
) {
    val key: String
        get() = "${width}x$height"

    val displayName: String
        get() = "$width × $height"

    companion object {
        internal fun fromOutputSizes(
            outputSizes: Iterable<Pair<Int, Int>>,
        ): List<CameraResolution> = outputSizes
            .distinct()
            .sortedByDescending { (width, height) -> width.toLong() * height }
            .map { (width, height) -> CameraResolution(width, height) }
    }
}

@ExperimentalCamera2Interop
internal fun CameraInfo.supportedCameraResolutions(): List<CameraResolution> = runCatching {
    val outputSizes = Camera2CameraInfo.from(this)
        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(SurfaceTexture::class.java)
        ?: return@runCatching emptyList()

    CameraResolution.fromOutputSizes(
        outputSizes.map { size -> size.width to size.height },
    )
}.getOrDefault(emptyList())
