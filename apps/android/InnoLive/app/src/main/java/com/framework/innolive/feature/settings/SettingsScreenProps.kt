package com.framework.innolive.feature.settings

import androidx.compose.runtime.Immutable
import com.framework.innolive.ui.text.UiText

@Immutable
data class SettingsScreenProps(
    val onBack: () -> Unit,
    val onOpenCameraSettings: () -> Unit,
    val onOpenBroadcastSettings: () -> Unit,
    val profileName: String,
    val profileEmail: String,
    val onLogout: () -> Unit,
    val onDeleteAccount: () -> Unit = {},
    val isDeletingAccount: Boolean = false,
    val isAccountDeletionPending: Boolean = false,
    val isAccountDeletionCleanupPending: Boolean = false,
    val accountDeletionError: UiText? = null,
    val onDeviceProcessing: Boolean = false,
    val canChangeAIProcessing: Boolean = false,
    val onSelectAIProcessing: (Boolean) -> Unit = {},
    val planUsage: PlanUsage? = null,
    val isPlanLoading: Boolean = false,
    val planError: UiText? = null,
    val onRefreshPlan: () -> Unit = {},
    val canReplayBroadcastGuide: Boolean = false,
    val onReplayBroadcastGuide: () -> Unit = {},
)
