package com.framework.innolive.feature.settings.broadcast

import androidx.compose.runtime.Immutable

@Immutable
data class BroadcastSettingProps(
    val onBack: () -> Unit,
    val selectedPlatform: String,
    val onOpenPlatformOptions: () -> Unit,
)
