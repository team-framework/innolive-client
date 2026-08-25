package com.framework.innolive.feature.live

import androidx.compose.runtime.Immutable

@Immutable
data class LiveScreenProps(
    val cameraLensFacing: CameraLensFacing,
    val cameraResolution: CameraResolution?,
    val broadcastSettings: BroadcastSettings,
    val onBroadcastSettingsChanged: (BroadcastSettings) -> Unit,
    val youtubeChannelTitle: String?,
    val youtubeAccountStatus: String,
    val isYouTubeReconnectRequired: Boolean,
    val isYouTubeAccountActionInProgress: Boolean,
    val isYouTubeConnectEnabled: Boolean,
    val onConnectYouTube: () -> Unit,
    val onRefreshAccessToken: suspend () -> String,
    val onOpenSettings: () -> Unit,
)
