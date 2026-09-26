package com.framework.innolive.feature.settings.camera

import androidx.compose.runtime.Immutable
import com.framework.innolive.feature.live.BroadcastVideoQualitySettings
import com.framework.innolive.feature.live.VideoQualityCaptureState

@Immutable
data class CameraSettingProps(
    val onBack: () -> Unit,
    val selectedResolution: String,
    val selectedCameraDevice: String,
    val selectedAudioDevice: String,
    val onOpenResolutionOptions: () -> Unit,
    val onOpenCameraDeviceOptions: () -> Unit,
    val onOpenAudioDeviceOptions: () -> Unit,
    val videoQualitySettings: BroadcastVideoQualitySettings = BroadcastVideoQualitySettings(),
    val onVideoQualitySettingsChanged: (BroadcastVideoQualitySettings) -> Unit = {},
    val videoQualityCaptureState: VideoQualityCaptureState = VideoQualityCaptureState(),
)
