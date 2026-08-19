package com.framework.innolive.feature.settings.camera

import androidx.compose.runtime.Immutable

@Immutable
data class CameraSettingProps(
    val onBack: () -> Unit,
    val selectedResolution: String,
    val selectedCameraDevice: String,
    val selectedAudioDevice: String,
    val onOpenResolutionOptions: () -> Unit,
    val onOpenCameraDeviceOptions: () -> Unit,
    val onOpenAudioDeviceOptions: () -> Unit,
)
