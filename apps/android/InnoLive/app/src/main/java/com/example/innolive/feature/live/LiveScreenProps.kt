package com.example.innolive.feature.live

import androidx.compose.runtime.Immutable

@Immutable
data class LiveScreenProps(
    val profileName: String,
    val profileEmail: String,
    val cameraLensFacing: CameraLensFacing,
    val cameraResolution: CameraResolution?,
    val onRefreshAccessToken: suspend () -> String,
    val onOpenSettings: () -> Unit,
    val onLogout: () -> Unit,
)
