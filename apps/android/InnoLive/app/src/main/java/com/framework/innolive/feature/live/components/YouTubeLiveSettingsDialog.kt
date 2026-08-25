package com.framework.innolive.feature.live.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.feature.live.BroadcastSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLiveSettingsDialog(
    settings: BroadcastSettings,
    youtubeChannelTitle: String?,
    youtubeAccountStatus: String,
    isYouTubeReconnectRequired: Boolean,
    isYouTubeAccountActionInProgress: Boolean,
    isYouTubeConnectEnabled: Boolean,
    onSettingsChanged: (BroadcastSettings) -> Unit,
    onConnectYouTube: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    var isPrivacyMenuExpanded by remember { mutableStateOf(false) }
    var isAudienceMenuExpanded by remember { mutableStateOf(false) }

    val privacyLabel = when (settings.privacy) {
        "unlisted" -> "일부 공개"
        "private" -> "비공개"
        else -> "공개"
    }
    val audienceLabel = when (settings.madeForKids) {
        true -> "아동용"
        false -> "아동용 아님"
        null -> "선택 필요"
    }
    val accountLabel = youtubeChannelTitle?.takeIf { it.isNotBlank() }
        ?: youtubeAccountStatus

    Dialog(onDismissRequest = onDismissRequest) {
        Surface (
            modifier = Modifier.width(300.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.9f),
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp, 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "라이브 설정",
                        modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black,
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "닫기",
                        )
                    }
                }

                OutlinedTextField(
                    value = settings.title,
                    onValueChange = { value ->
                        onSettingsChanged(settings.copy(title = value.take(100)))
                    },
                    label = { Text("방송 제목") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = settings.description,
                    onValueChange = { value ->
                        onSettingsChanged(settings.copy(description = value.take(5_000)))
                    },
                    label = { Text("방송 설명") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(
                    expanded = isPrivacyMenuExpanded,
                    onExpandedChange = { isPrivacyMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = privacyLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("공개 범위") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = isPrivacyMenuExpanded,
                            )
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = isPrivacyMenuExpanded,
                        onDismissRequest = { isPrivacyMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("공개") },
                            onClick = {
                                onSettingsChanged(settings.copy(privacy = "public"))
                                isPrivacyMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("일부 공개") },
                            onClick = {
                                onSettingsChanged(settings.copy(privacy = "unlisted"))
                                isPrivacyMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("비공개") },
                            onClick = {
                                onSettingsChanged(settings.copy(privacy = "private"))
                                isPrivacyMenuExpanded = false
                            },
                        )
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = isAudienceMenuExpanded,
                    onExpandedChange = { isAudienceMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = audienceLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("아동용 설정") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = isAudienceMenuExpanded,
                            )
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = isAudienceMenuExpanded,
                        onDismissRequest = { isAudienceMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("아동용") },
                            onClick = {
                                onSettingsChanged(settings.copy(madeForKids = true))
                                isAudienceMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("아동용 아님") },
                            onClick = {
                                onSettingsChanged(settings.copy(madeForKids = false))
                                isAudienceMenuExpanded = false
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "계정 정보",
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = accountLabel)
                    if (youtubeChannelTitle.isNullOrBlank() || isYouTubeReconnectRequired) {
                        Button(
                            onClick = onConnectYouTube,
                            enabled = isYouTubeConnectEnabled && !isYouTubeAccountActionInProgress,
                        ) {
                            Text(
                                text = if (isYouTubeReconnectRequired) "재연동" else "연동",
                            )
                        }
                    }
                }
            }
        }
    }
}
