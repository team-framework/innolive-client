package com.framework.innolive.feature.settings.broadcast

import androidx.compose.runtime.Immutable

@Immutable
data class BroadcastSettingProps(
    val onBack: () -> Unit,
    val selectedPlatform: String,
    val onOpenPlatformOptions: () -> Unit,
    val title: String,
    val onTitleChanged: (String) -> Unit,
    val description: String,
    val onDescriptionChanged: (String) -> Unit,
    val selectedPrivacy: String,
    val onOpenPrivacyOptions: () -> Unit,
    val selectedAudience: String,
    val onOpenAudienceOptions: () -> Unit,
    val categoryId: String,
    val onCategoryIdChanged: (String) -> Unit,
    val onSave: () -> Unit,
    val isSaveEnabled: Boolean,
    val statusMessage: String,
)
