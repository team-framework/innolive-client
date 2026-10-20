package com.framework.innolive.feature.live

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

internal const val MAX_CAMERA_LONG_EDGE = 1_920
internal const val MAX_CAMERA_SHORT_EDGE = 1_080

internal fun isWithinCameraResolutionLimit(width: Int, height: Int): Boolean =
    maxOf(width, height) <= MAX_CAMERA_LONG_EDGE &&
        minOf(width, height) <= MAX_CAMERA_SHORT_EDGE

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

/**
 * 선택한 해상도가 지원 목록에 있으면 유지하고, 없을 때만 가장 큰 해상도로 대체합니다.
 * 지원 목록을 아직 읽지 못했으면(null) 해상도를 정하지 않습니다.
 */
internal fun selectCameraResolution(
    supportedResolutions: List<CameraResolution>?,
    selectedKey: String?,
): CameraResolution? = supportedResolutions?.let { resolutions ->
    resolutions.firstOrNull { resolution -> resolution.key == selectedKey } ?: resolutions.firstOrNull()
}

internal fun ProcessCameraProvider.supportedCameraResolutions(
    lensFacing: CameraLensFacing,
): List<CameraResolution> = runCatching {
    val cameraSelector = when (lensFacing) {
        CameraLensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraLensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }
    cameraSelector
        .filter(availableCameraInfos)
        .firstOrNull()
        ?.supportedCameraResolutions()
        .orEmpty()
}.getOrDefault(emptyList())

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal fun CameraInfo.supportedCameraResolutions(): List<CameraResolution> = runCatching {
    val outputSizes = Camera2CameraInfo.from(this)
        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(SurfaceTexture::class.java)
        ?: return@runCatching emptyList()

    CameraResolution.fromOutputSizes(
        outputSizes.map { size -> size.width to size.height },
    )
}.getOrDefault(emptyList())
