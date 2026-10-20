package com.framework.innolive.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.ui.text.asString
import com.framework.innolive.ui.theme.InnoLiveAlertDialog

/** iOS 계정 설정처럼 로그아웃과 계정 삭제를 모은 설정 하위 화면입니다. 뒤로 가기는 앱 내비게이션이 받습니다. */
@Composable
fun AccountSettingsScreen(props: SettingsScreenProps, onBack: () -> Unit) {
    var isDeleteConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val confirmDeletionDescription =
        stringResource(R.string.content_description_confirm_delete_account)
    val enabled = !props.isDeletingAccount && !props.isAccountDeletionPending

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = enabled) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsLinkRow(
                label = stringResource(R.string.action_logout),
                enabled = enabled,
                onClick = props.onLogout,
                color = MaterialTheme.colorScheme.error,
                showChevron = false,
            )
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
