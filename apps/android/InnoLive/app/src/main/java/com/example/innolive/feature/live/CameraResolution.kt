package com.example.innolive.feature.live

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

enum class CameraResolution(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val displayName: String,
) {
    FULL_HD_30(1920, 1080, 30, "1080p - 30fps"),
    FULL_HD_24(1920, 1080, 24, "1080p - 24fps"),
    HD_30(1280, 720, 30, "720p - 30fps"),
    HD_24(1280, 720, 24, "720p - 24fps"),

    ;

    companion object {
        internal fun supportedBy(
            outputSizes: Set<Pair<Int, Int>>,
        ): List<CameraResolution> = entries.filter { resolution ->
            resolution.width to resolution.height in outputSizes
        }
    }
}

@ExperimentalCamera2Interop
internal fun CameraInfo.supportedCameraResolutions(): List<CameraResolution> = runCatching {
    val outputSizes = Camera2CameraInfo.from(this)
        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(SurfaceTexture::class.java)
        ?: return@runCatching CameraResolution.entries

    CameraResolution.supportedBy(
        outputSizes.mapTo(mutableSetOf()) { size -> size.width to size.height },
    )
}.getOrDefault(CameraResolution.entries)
