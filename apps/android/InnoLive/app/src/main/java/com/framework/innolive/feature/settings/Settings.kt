package com.framework.innolive.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.VideoCameraBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.framework.innolive.feature.live.ProfileDisplay

data class SettingsMenuItem(
    val icon: ImageVector,
    val label: String,
    val onNav: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(props: SettingsScreenProps) {
    var isDeleteConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val settingItems = listOf(
        SettingsMenuItem(Icons.Outlined.VideoCameraBack, "카메라 및 오디오 설정", props.onOpenCameraSettings),
        SettingsMenuItem(Icons.Outlined.CloudUpload, "방송 설정", props.onOpenBroadcastSettings),
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            space = 10.dp
        )
    ) {
        TopAppBar(
            title = { Text(text = "설정") },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = props.onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "뒤로가기",
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
            OutlinedButton(onClick = props.onLogout, enabled = !props.isDeletingAccount) {
                Text(text = "로그아웃")
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 0.dp),
        ) {
            settingItems.forEach { item ->
                Button(
                    onClick = item.onNav,
                    enabled = !props.isDeletingAccount,
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
                            contentDescription = item.label
                        )
                        Text(text = item.label)
                    }
                }
            }
            OutlinedButton(
                onClick = { isDeleteConfirmationVisible = true },
                enabled = !props.isDeletingAccount,
            ) {
                Text(text = if (props.isDeletingAccount) "계정 삭제 중…" else "계정 삭제")
            }
            props.accountDeletionError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    if (isDeleteConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { isDeleteConfirmationVisible = false },
            title = { Text(text = "계정을 삭제할까요?") },
            text = {
                Text(text = "계정 삭제가 완료되면 로그아웃되며 이 기기의 YouTube 연결과 방송 설정이 초기화됩니다.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleteConfirmationVisible = false
                        props.onDeleteAccount()
                    },
                    enabled = !props.isDeletingAccount,
                    modifier = Modifier.semantics { contentDescription = "계정 삭제 확인" },
                ) {
                    Text(text = "계정 삭제")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { isDeleteConfirmationVisible = false }) {
                    Text(text = "취소")
                }
            },
        )
    }
}
