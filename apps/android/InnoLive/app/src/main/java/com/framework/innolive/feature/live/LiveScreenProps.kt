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
    /** 설정에서도 영상 조절을 열 수 있게 앱이 열림 상태를 가질 때 넘깁니다. null이면 화면이 직접 가집니다. */
    val isVideoControlsOpen: Boolean? = null,
    val onVideoControlsOpenChange: (Boolean) -> Unit = {},
)
