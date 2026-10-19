package com.framework.innolive.feature.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.ProfileDisplay
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
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
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
            SettingsDivider()
            SettingsSectionLabel(stringResource(R.string.settings_section_audio))
            SettingsValueRow(stringResource(R.string.label_audio_device), props.selectedAudioDevice,
                enabled, props.onOpenAudioDeviceOptions)
            TextButton(onClick = props.onOpenCameraSettings, enabled = enabled) {
                Text(stringResource(R.string.settings_camera_audio))
            }
            SettingsDivider()
            SettingsSectionLabel(stringResource(R.string.settings_section_live))
            SettingsValueRow(stringResource(R.string.label_broadcast_platform), props.selectedPlatform,
                enabled, props.onOpenBroadcastSettings)
            SettingsValueRow(stringResource(R.string.label_broadcast_account), props.broadcastAccount,
                enabled, props.onOpenBroadcastSettings)
            TextButton(onClick = props.onOpenBroadcastSettings, enabled = enabled) {
                Text(stringResource(R.string.settings_broadcast))
            }
            SettingsDivider()
            PlanUsageSettings(props)
            AIProcessingSettings(props)
            SettingsDivider()
            TextButton(onClick = { uriHandler.openUri(privacyPolicyUrl) }, enabled = enabled) {
                Text(stringResource(R.string.privacy_policy))
            }
            TextButton(onClick = props.onReplayBroadcastGuide,
                enabled = enabled && props.canReplayBroadcastGuide) {
                Text(stringResource(R.string.tutorial_replay))
            }
            OutlinedButton(
                onClick = { isDeleteConfirmationVisible = true },
                enabled = !props.isDeletingAccount,
            ) {
                Text(
                    text = when {
                        props.isDeletingAccount -> stringResource(R.string.action_deleting_account)
                        props.isAccountDeletionCleanupPending ->
                            stringResource(R.string.action_retry_device_cleanup)
                        else -> stringResource(R.string.action_delete_account)
                    },
                )
            }
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
        AlertDialog(
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
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).selectableGroup()) {
        Text(stringResource(R.string.settings_ai_processing), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ai_mode_change_availability),
            style = MaterialTheme.typography.bodySmall)
        listOf(false to R.string.ai_mode_server, true to R.string.ai_mode_on_device).forEach { (onDevice, label) ->
            val selected = props.onDeviceProcessing == onDevice
            Row(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { if (!selected) props.onSelectAIProcessing(onDevice) },
                ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Figma 설정 창의 구역 이름처럼 작고 가는 글씨로 보여 줍니다. */
@Composable
private fun SettingsSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Light,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SettingsDivider() {
    Image(
        painter = painterResource(R.drawable.settings_divider),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(3.dp),
    )
}

@Composable
private fun SettingsValueRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f))
    }
}
