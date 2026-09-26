package com.framework.innolive.feature.live

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import java.util.concurrent.Executor

/** All camera control and state publication runs on the main executor. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal class CameraVideoQualityController(
    private val mainExecutor: Executor,
    private val onStateChanged: (VideoQualityCaptureState) -> Unit,
) : AutoCloseable {
    private var camera: Camera? = null
    private var settings = BroadcastVideoQualitySettings()
    private var state = VideoQualityCaptureState()
    private var modes = emptySet<Int>()
    private var requestedMode = VideoQualityCapturePolicy.STABILIZATION_OFF
    private var exposureIndex = 0
    private var exposureStep = 0f
    private var standardFallback = false
    private var generation = 0
    @Volatile private var closed = false

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (closed) return
            mainExecutor.execute {
                if (closed || camera == null) return@execute
                // Late results from a previous slider/toggle request cannot overwrite current state.
                if (request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE) != requestedMode) return@execute
                val activeMode = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                if (requestedMode == VideoQualityCapturePolicy.STABILIZATION_PREVIEW &&
                    activeMode == VideoQualityCapturePolicy.STABILIZATION_OFF &&
                    VideoQualityCapturePolicy.STABILIZATION_STANDARD in modes && !standardFallback
                ) {
                    standardFallback = true
                    applyControls()
                    return@execute
                }
                val actualIndex = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                publish(state.copy(
                    appliedExposureEV = actualIndex?.let { it * exposureStep } ?: state.appliedExposureEV,
                    stabilizationStatus = VideoQualityCapturePolicy.stabilizationStatus(
                        settings.stabilizationEnabled, requestedMode, activeMode,
                    ),
                ))
            }
        }
    }

    fun bind(camera: Camera) {
        if (closed) return
        this.camera = camera
        modes = Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toSet().orEmpty()
        val exposure = camera.cameraInfo.exposureState
        exposureStep = exposure.exposureCompensationStep.toFloat()
        val range = VideoQualityCapturePolicy.exposureIndices(
            exposure.exposureCompensationRange.lower, exposure.exposureCompensationRange.upper, exposureStep,
        )
        publish(VideoQualityCaptureState(
            exposureSupported = exposure.isExposureCompensationSupported && range != null && range.first < range.last,
            minExposureEV = range?.first?.times(exposureStep) ?: 0f,
            maxExposureEV = range?.last?.times(exposureStep) ?: 0f,
            appliedExposureEV = exposure.exposureCompensationIndex * exposureStep,
        ))
        applyControls()
    }

    fun update(settings: BroadcastVideoQualitySettings) {
        val normalized = settings.normalized()
        val captureChanged = this.settings.stabilizationEnabled != normalized.stabilizationEnabled ||
            this.settings.exposureEV != normalized.exposureEV
        if (this.settings.stabilizationEnabled != normalized.stabilizationEnabled) standardFallback = false
        this.settings = normalized
        if (captureChanged) applyControls()
    }

    private fun applyControls() {
        val camera = camera ?: return
        val operation = ++generation
        requestedMode = VideoQualityCapturePolicy.stabilizationMode(
            settings.stabilizationEnabled, modes,
            supportsPreview = Build.VERSION.SDK_INT >= 33 && !standardFallback,
        )
        publish(state.copy(stabilizationStatus =
            if (settings.stabilizationEnabled && requestedMode == VideoQualityCapturePolicy.STABILIZATION_OFF) {
                VideoStabilizationStatus.UNSUPPORTED
            } else VideoStabilizationStatus.PENDING,
        ))
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, requestedMode)
            .build()
        val stabilization = Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(options)
        stabilization.addListener({
            if (!closed && operation == generation) {
                runCatching { stabilization.get() }.onFailure {
                    if (!standardFallback && requestedMode == VideoQualityCapturePolicy.STABILIZATION_PREVIEW &&
                        VideoQualityCapturePolicy.STABILIZATION_STANDARD in modes
                    ) {
                        standardFallback = true
                        applyControls()
                    } else {
                        publish(state.copy(stabilizationStatus = VideoStabilizationStatus.UNSUPPORTED))
                    }
                }
            }
        }, mainExecutor)
        if (state.exposureSupported) {
            val exposure = camera.cameraInfo.exposureState
            exposureIndex = VideoQualityCapturePolicy.exposureIndex(
                settings.exposureEV, exposure.exposureCompensationRange.lower,
                exposure.exposureCompensationRange.upper, exposureStep,
            )
            val adjustment = camera.cameraControl.setExposureCompensationIndex(exposureIndex)
            adjustment.addListener({
                if (!closed && operation == generation) {
                    runCatching { adjustment.get() }.onSuccess { appliedIndex ->
                        publish(state.copy(appliedExposureEV = appliedIndex * exposureStep))
                    }
                }
            }, mainExecutor)
        }
    }

    private fun publish(value: VideoQualityCaptureState) {
        if (state == value || closed) return
        state = value
        onStateChanged(value)
    }

    override fun close() {
        closed = true
        generation++
        camera = null
    }
}
