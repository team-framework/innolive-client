package com.framework.innolive.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.VideoCameraBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.ProfileDisplay
import com.framework.innolive.ui.text.asString

data class SettingsMenuItem(
    val icon: ImageVector,
    val label: String,
    val onNav: () -> Unit
)

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
    val settingItems = listOf(
        SettingsMenuItem(
            Icons.Outlined.VideoCameraBack,
            stringResource(R.string.settings_camera_audio),
            props.onOpenCameraSettings,
        ),
        SettingsMenuItem(
            Icons.Outlined.CloudUpload,
            stringResource(R.string.settings_broadcast),
            props.onOpenBroadcastSettings,
        ),
        SettingsMenuItem(
            Icons.Outlined.Description,
            stringResource(R.string.privacy_policy),
        ) {
            uriHandler.openUri(privacyPolicyUrl)
        },
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(
            space = 10.dp
        )
    ) {
        TopAppBar(
            title = { Text(text = stringResource(R.string.settings_title)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(
                    onClick = props.onBack,
                    enabled = !props.isDeletingAccount && !props.isAccountDeletionPending,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
        )
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ProfileDisplay(
                name = props.profileName,
                email = props.profileEmail,
            )
            OutlinedButton(
                onClick = props.onLogout,
                enabled = !props.isDeletingAccount && !props.isAccountDeletionPending,
            ) {
                Text(text = stringResource(R.string.action_logout))
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        ) {
            settingItems.forEach { item ->
                Button(
                    onClick = item.onNav,
                    enabled = !props.isDeletingAccount && !props.isAccountDeletionPending,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            space = 8.dp,
                            alignment = Alignment.Start
                        )
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                        )
                        Text(text = item.label)
                    }
                }
            }
            AIProcessingSettings(props)
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
    val enabled = props.canChangeAIProcessing && !props.isAIProcessingChanging &&
        !props.isDeletingAccount && !props.isAccountDeletionPending
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
        if (props.isAIProcessingChanging) {
            Text(stringResource(R.string.ai_mode_change_in_progress),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else if (props.aiProcessingChangeFailed) {
            Text(stringResource(R.string.ai_mode_change_failed), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
    }
}
