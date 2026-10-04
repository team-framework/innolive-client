package com.framework.innolive.feature.live

import androidx.compose.runtime.Immutable
import com.framework.innolive.feature.settings.PlanUsage

@Immutable
data class LiveScreenProps(
    val cameraLensFacing: CameraLensFacing,
    val cameraResolution: CameraResolution?,
    val broadcastSettings: BroadcastSettings,
    val onBroadcastSettingsChanged: (BroadcastSettings) -> Unit,
    val youtubeChannelTitle: String?,
    val hasYouTubeAccount: Boolean,
    val youtubeAccountStatus: String,
    val isYouTubeReconnectRequired: Boolean,
    val isYouTubeAccountActionInProgress: Boolean,
    val isYouTubeConnectEnabled: Boolean,
    val onConnectYouTube: () -> Unit,
    val onRefreshAccessToken: suspend () -> String,
    val onOpenSettings: () -> Unit,
    val onAuthenticationExpired: () -> Unit = {},
    val onGetAccessToken: () -> String? = { null },
    val profileEmail: String = "",
    val canSwitchCamera: Boolean = false,
    val onSwitchCamera: () -> Unit = {},
    val videoQualitySettings: BroadcastVideoQualitySettings = BroadcastVideoQualitySettings(),
    val onVideoQualitySettingsChanged: (BroadcastVideoQualitySettings) -> Unit = {},
    val videoQualityCaptureState: VideoQualityCaptureState = VideoQualityCaptureState(),
    val onVideoQualityCaptureStateChanged: (VideoQualityCaptureState) -> Unit = {},
    val planUsage: PlanUsage? = null,
    val onOpenPlan: () -> Unit = onOpenSettings,
)
