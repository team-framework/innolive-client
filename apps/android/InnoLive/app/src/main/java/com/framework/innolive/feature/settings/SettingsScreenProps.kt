package com.framework.innolive.feature.settings

import androidx.compose.runtime.Immutable
import com.framework.innolive.feature.live.BroadcastVideoQualitySettings
import com.framework.innolive.feature.live.VideoQualityCaptureState
import com.framework.innolive.ui.text.UiText

@Immutable
data class SettingsScreenProps(
    val onBack: () -> Unit,
    val onOpenBroadcastSettings: () -> Unit,
    val profileName: String,
    val profileEmail: String,
    val onLogout: () -> Unit,
    val selectedResolution: String = "",
    val selectedCameraDevice: String = "",
    val selectedAudioDevice: String = "",
    val selectedPlatform: String = "",
    val broadcastAccount: String = "",
    val onOpenResolutionOptions: () -> Unit = {},
    val onOpenCameraDeviceOptions: () -> Unit = {},
    val onOpenAudioDeviceOptions: () -> Unit = {},
    val videoQualitySettings: BroadcastVideoQualitySettings = BroadcastVideoQualitySettings(),
    val onVideoQualitySettingsChanged: (BroadcastVideoQualitySettings) -> Unit = {},
    val videoQualityCaptureState: VideoQualityCaptureState = VideoQualityCaptureState(),
    val onDeleteAccount: () -> Unit = {},
    val isDeletingAccount: Boolean = false,
    val isAccountDeletionPending: Boolean = false,
    val isAccountDeletionCleanupPending: Boolean = false,
    val accountDeletionError: UiText? = null,
    val onDeviceProcessing: Boolean = false,
    val canChangeAIProcessing: Boolean = false,
    val onSelectAIProcessing: (Boolean) -> Unit = {},
    /** `GET /admin/me`로 확인한 관리자 계정입니다. 관리자에게만 실험실을 보여 줍니다. */
    val isAdministrator: Boolean = false,
    val faceSwapEnabled: Boolean = false,
    val onFaceSwapChanged: (Boolean) -> Unit = {},
    val planUsage: PlanUsage? = null,
    val isPlanLoading: Boolean = false,
    val planError: UiText? = null,
    val onRefreshPlan: () -> Unit = {},
    val canReplayBroadcastGuide: Boolean = false,
    val onReplayBroadcastGuide: () -> Unit = {},
    val onOpenRecordings: () -> Unit = {},
    /** 방송 준비·방송 중에는 false입니다. */
    val canOpenRecordings: Boolean = true,
)
