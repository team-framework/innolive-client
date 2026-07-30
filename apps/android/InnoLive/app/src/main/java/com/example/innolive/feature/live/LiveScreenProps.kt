package com.example.innolive.feature.live

import androidx.compose.runtime.Immutable

@Immutable
data class LiveScreenProps(
    val onOpenSettings: () -> Unit,
)
