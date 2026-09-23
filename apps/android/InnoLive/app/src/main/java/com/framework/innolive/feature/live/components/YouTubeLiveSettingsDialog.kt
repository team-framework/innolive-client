package com.framework.innolive.feature.live.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLiveSettingsDialog(
    settings: BroadcastSettings,
    youtubeChannelTitle: String?,
    hasYouTubeAccount: Boolean,
    youtubeAccountStatus: String,
    isYouTubeReconnectRequired: Boolean,
    isYouTubeAccountActionInProgress: Boolean,
    isYouTubeConnectEnabled: Boolean,
    onSettingsChanged: (BroadcastSettings) -> Unit,
    onConnectYouTube: () -> Unit,
    onDismissRequest: () -> Unit,
    onPrepare: (() -> Unit)? = null,
) {
    var isPrivacyMenuExpanded by remember { mutableStateOf(false) }
    var isAudienceMenuExpanded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val dialogWidth = screenWidthDp * 0.9f
    val dialogMaxHeight = configuration.screenHeightDp.dp * 0.9f

    var validation by remember { mutableStateOf(YouTubeLiveSettingsValidation()) }

    val privacyLabel = when (settings.privacy) {
        "unlisted" -> stringResource(R.string.privacy_unlisted)
        "private" -> stringResource(R.string.privacy_private)
        else -> stringResource(R.string.privacy_public)
    }
    val audienceLabel = when (settings.madeForKids) {
        true -> stringResource(R.string.audience_made_for_kids)
        false -> stringResource(R.string.audience_not_made_for_kids)
        null -> stringResource(R.string.audience_required)
    }
    val accountLabel = youtubeAccountStatus
    val canPrepare = hasYouTubeAccount &&
        !isYouTubeReconnectRequired && !isYouTubeAccountActionInProgress

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor = when {
        isPressed -> Color(0xFFD2D2D2)
        isHovered -> Color(0xFFF2F2F2)
        else -> Color.White
    }

    fun validateForm() {
        validation = validateYouTubeLiveSettings(settings)
        if (validation.isValid) {
            if (onPrepare == null) onDismissRequest()
            else if (canPrepare) onPrepare()
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface (
            modifier = Modifier
                .width(dialogWidth)
                .widthIn(max = 600.dp)
                .heightIn(max = dialogMaxHeight),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.live_settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onDismissRequest, modifier = Modifier.offset(6.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                }

                OutlinedTextField(
                    value = settings.title,
                    onValueChange = { value ->
                        validation = validation.copy(titleError = false)
                        onSettingsChanged(settings.copy(title = value.take(MAX_YOUTUBE_TITLE_LENGTH)))
                    },
                    label = {
                        Text(
                            stringResource(R.string.label_broadcast_title),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    singleLine = true,
                    isError = validation.titleError,
                    supportingText = if (validation.titleError) {
                        {
                            Text(
                                stringResource(R.string.validation_broadcast_title),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = settings.description,
                    onValueChange = { value ->
                        validation = validation.copy(descriptionError = false)
                        onSettingsChanged(
                            settings.copy(description = value.take(MAX_YOUTUBE_DESCRIPTION_LENGTH)),
                        )
                    },
                    label = {
                        Text(
                            stringResource(R.string.label_broadcast_description),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    minLines = 3,
                    maxLines = 5,
                    isError = validation.descriptionError,
                    supportingText = if (validation.descriptionError) {
                        {
                            Text(
                                stringResource(R.string.validation_broadcast_description),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        null
                    },
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
                        label = {
                            Text(
                                stringResource(R.string.label_broadcast_privacy),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
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
                            text = {
                                Text(
                                    stringResource(R.string.privacy_public),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                onSettingsChanged(settings.copy(privacy = "public"))
                                isPrivacyMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.privacy_unlisted),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                onSettingsChanged(settings.copy(privacy = "unlisted"))
                                isPrivacyMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.privacy_private),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
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
                        isError = validation.audienceError,
                        label = {
                            Text(
                                stringResource(R.string.label_made_for_kids),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingText = if (validation.audienceError) {
                            {
                                Text(
                                    stringResource(R.string.validation_audience),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        } else {
                            null
                        },
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
                            text = {
                                Text(
                                    stringResource(R.string.audience_made_for_kids),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                onSettingsChanged(settings.copy(madeForKids = true))
                                validation = validation.copy(audienceError = false)
                                isAudienceMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.audience_not_made_for_kids),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                onSettingsChanged(settings.copy(madeForKids = false))
                                validation = validation.copy(audienceError = false)
                                isAudienceMenuExpanded = false
                            },
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(0.dp, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.label_account_information),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = accountLabel,
                            modifier = Modifier.weight(1f),
                        )
                        if (!hasYouTubeAccount || isYouTubeReconnectRequired) {
                            Button(
                                onClick = onConnectYouTube,
                                enabled = isYouTubeConnectEnabled && !isYouTubeAccountActionInProgress,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (isYouTubeReconnectRequired) {
                                            R.string.action_reconnect
                                        } else {
                                            R.string.action_connect
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .fillMaxWidth()
                        .clickable(
                            enabled = onPrepare == null || canPrepare,
                            role = Role.Button,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { validateForm() },
                        )
                        .background(backgroundColor, RoundedCornerShape(20.dp))
                        .hoverable(interactionSource),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (onPrepare == null) R.string.action_save_and_close
                            else R.string.action_prepare_broadcast,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black,
                    )
                }
            }
        }
    }
}
