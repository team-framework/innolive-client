package com.framework.innolive.feature.settings

import androidx.compose.runtime.Immutable

@Immutable
data class SettingsScreenProps(
    val onBack: () -> Unit,
    val onOpenCameraSettings: () -> Unit,
    val onOpenBroadcastSettings: () -> Unit,
    val profileName: String,
    val profileEmail: String,
    val onLogout: () -> Unit,
)
