package com.framework.innolive.feature.settings.broadcast

import androidx.annotation.StringRes
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
    val youtubeChannelTitle: String?,
    val youtubeAccountStatus: String,
    val hasVerifiedYouTubeAccount: Boolean,
    val isYouTubeReconnectRequired: Boolean,
    val isYouTubeAccountActionInProgress: Boolean,
    val isYouTubeConnectEnabled: Boolean,
    @param:StringRes val connectDisabledReasonRes: Int?,
    val onConnectYouTube: () -> Unit,
    val onSave: () -> Unit,
    val isSaveEnabled: Boolean,
    @param:StringRes val saveDisabledReasonRes: Int?,
    val statusMessage: String,
)
