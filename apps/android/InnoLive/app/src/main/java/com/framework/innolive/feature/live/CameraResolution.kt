package com.framework.innolive.feature.live

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

internal const val MAX_CAMERA_LONG_EDGE = 1_920
internal const val MAX_CAMERA_SHORT_EDGE = 1_080

internal fun isWithinCameraResolutionLimit(width: Int, height: Int): Boolean =
    maxOf(width, height) <= MAX_CAMERA_LONG_EDGE &&
        minOf(width, height) <= MAX_CAMERA_SHORT_EDGE

/** On-device AI and an encoder share the GPU; start at HD unless the user chooses otherwise. */
internal fun defaultCameraResolution(options: List<CameraResolution>, onDevice: Boolean): CameraResolution? =
    if (onDevice) options.firstOrNull { it.width.toLong() * it.height <= 1280L * 720L }
        ?: options.firstOrNull()
    else options.firstOrNull()

internal fun selectedCameraResolution(options: List<CameraResolution>, explicitKey: String?,
                                      onDevice: Boolean): CameraResolution? =
    options.firstOrNull { it.key == explicitKey } ?: defaultCameraResolution(options, onDevice)

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
            .filter { (width, height) -> isWithinCameraResolutionLimit(width, height) }
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
