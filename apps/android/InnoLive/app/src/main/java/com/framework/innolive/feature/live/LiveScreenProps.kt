package com.framework.innolive.feature.live

import androidx.compose.runtime.Immutable

@Immutable
data class LiveScreenProps(
    val cameraLensFacing: CameraLensFacing,
    val cameraResolution: CameraResolution?,
    val onRefreshAccessToken: suspend () -> String,
    val onOpenSettings: () -> Unit,
)
