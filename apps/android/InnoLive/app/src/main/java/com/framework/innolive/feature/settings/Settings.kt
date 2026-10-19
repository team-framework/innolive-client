package com.framework.innolive.feature.settings

import com.framework.innolive.ui.theme.InnoLiveAlertDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.ProfileDisplay
import com.framework.innolive.feature.settings.camera.CameraStabilizationControl
import com.framework.innolive.ui.text.asString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(props: SettingsScreenProps) {
    var isDeleteConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val confirmDeletionDescription =
        stringResource(R.string.content_description_confirm_delete_account)
    val uriHandler = LocalUriHandler.current
    val privacyPolicyUrl = privacyPolicyUrlForLanguage(
        LocalConfiguration.current.locales[0].language,
    )
    val enabled = !props.isDeletingAccount && !props.isAccountDeletionPending

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = props.onBack, enabled = enabled) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsSectionLabel(stringResource(R.string.settings_section_account))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ProfileDisplay(name = props.profileName, email = props.profileEmail)
                }
                OutlinedButton(onClick = props.onLogout, enabled = enabled) {
                    Text(stringResource(R.string.action_logout))
                }
            }
            SettingsDivider()
            SettingsSectionLabel(stringResource(R.string.settings_section_camera))
            SettingsValueRow(stringResource(R.string.label_camera_resolution), props.selectedResolution,
                enabled, props.onOpenResolutionOptions)
            SettingsValueRow(stringResource(R.string.label_camera_device), props.selectedCameraDevice,
                enabled, props.onOpenCameraDeviceOptions)
            CameraStabilizationControl(
                settings = props.videoQualitySettings,
                captureState = props.videoQualityCaptureState,
                onSettingsChanged = props.onVideoQualitySettingsChanged,
                enabled = enabled,
            )
            SettingsDivider()
            SettingsSectionLabel(stringResource(R.string.settings_section_audio))
            SettingsValueRow(stringResource(R.string.label_audio_device), props.selectedAudioDevice,
                enabled, props.onOpenAudioDeviceOptions)
            SettingsDivider()
            SettingsSectionLabel(stringResource(R.string.settings_section_live))
            SettingsValueRow(stringResource(R.string.label_broadcast_platform), props.selectedPlatform,
                enabled, props.onOpenBroadcastSettings)
            SettingsValueRow(stringResource(R.string.label_broadcast_account), props.broadcastAccount,
                enabled, props.onOpenBroadcastSettings)
            SettingsLinkRow(stringResource(R.string.settings_broadcast), enabled, props.onOpenBroadcastSettings)
            // iOS처럼 방송 준비·방송 중에는 보관함에 들어가지 않습니다.
            SettingsLinkRow(stringResource(R.string.settings_recordings), enabled && props.canOpenRecordings,
                props.onOpenRecordings)
            SettingsDivider()
            PlanUsageSettings(props)
            AIProcessingSettings(props)
            SettingsDivider()
            SettingsLinkRow(stringResource(R.string.privacy_policy), enabled, { uriHandler.openUri(privacyPolicyUrl) })
            SettingsLinkRow(stringResource(R.string.tutorial_replay), enabled && props.canReplayBroadcastGuide,
                props.onReplayBroadcastGuide)
            // 되돌릴 수 없는 동작이라 다른 줄과 구분되도록 경고 색으로 보여 줍니다.
            SettingsLinkRow(
                label = when {
                    props.isDeletingAccount -> stringResource(R.string.action_deleting_account)
                    props.isAccountDeletionCleanupPending -> stringResource(R.string.action_retry_device_cleanup)
                    else -> stringResource(R.string.action_delete_account)
                },
                enabled = !props.isDeletingAccount,
                onClick = { isDeleteConfirmationVisible = true },
                color = MaterialTheme.colorScheme.error,
                showChevron = false,
            )
            props.accountDeletionError?.let { message ->
                Text(
                    text = message.asString(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
    }

    if (isDeleteConfirmationVisible) {
        InnoLiveAlertDialog(
            onDismissRequest = { isDeleteConfirmationVisible = false },
            title = {
                Text(
                    text = if (props.isAccountDeletionCleanupPending) {
                        stringResource(R.string.retry_device_cleanup_title)
                    } else {
                        stringResource(R.string.delete_account_title)
                    },
                )
            },
            text = {
                Text(
                    text = if (props.isAccountDeletionCleanupPending) {
                        stringResource(R.string.retry_device_cleanup_description)
                    } else {
                        stringResource(R.string.delete_account_description)
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleteConfirmationVisible = false
                        props.onDeleteAccount()
                    },
                    enabled = !props.isDeletingAccount,
                    modifier = Modifier.semantics {
                        contentDescription = confirmDeletionDescription
                    },
                ) {
                    Text(text = stringResource(R.string.action_delete_account))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { isDeleteConfirmationVisible = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AIProcessingSettings(props: SettingsScreenProps) {
    val enabled = props.canChangeAIProcessing && !props.isDeletingAccount &&
        !props.isAccountDeletionPending
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).selectableGroup()) {
        SettingsSectionLabel(stringResource(R.string.settings_ai_processing))
        Text(stringResource(R.string.ai_mode_change_availability),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp))
        listOf(false to R.string.ai_mode_server, true to R.string.ai_mode_on_device).forEach { (onDevice, label) ->
            val selected = props.onDeviceProcessing == onDevice
            Row(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { if (!selected) props.onSelectAIProcessing(onDevice) },
                ).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    Image(
        painter = painterResource(R.drawable.settings_divider),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(3.dp),
    )
}
