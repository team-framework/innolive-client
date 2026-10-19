package com.framework.innolive.feature.live.components

import com.framework.innolive.ui.theme.ModalSurface
import com.framework.innolive.ui.theme.InnoLiveDialog
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialAnchor
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialDialogFrame
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialGuide
import com.framework.innolive.feature.live.tutorial.broadcastTutorialAnchor
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.asString

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
    /** 준비 버튼 문구입니다. 동시 송출에서는 치지직 설정으로 넘어가는 문구를 씁니다. */
    @StringRes prepareActionRes: Int = R.string.action_prepare_broadcast,
    serverError: ServerErrorGuidance? = null,
    onChangePlatform: (() -> Unit)? = null,
    guide: BroadcastTutorialGuide? = null,
) {

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val dialogWidth = screenWidthDp * 0.9f
    val dialogMaxHeight = configuration.screenHeightDp.dp * 0.9f

    var validation by remember { mutableStateOf(YouTubeLiveSettingsValidation()) }
    val canPrepare = hasYouTubeAccount &&
        !isYouTubeReconnectRequired && !isYouTubeAccountActionInProgress

    fun validateForm() {
        validation = validateYouTubeLiveSettings(settings)
        if (validation.isValid) {
            if (onPrepare == null) onDismissRequest()
            else if (canPrepare) onPrepare()
        }
    }

    InnoLiveDialog(onDismissRequest = onDismissRequest) {
        ModalSurface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .width(dialogWidth)
                .heightIn(max = dialogMaxHeight),
        ) {
            BroadcastTutorialDialogFrame(guide) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BroadcastDialogHeader(
                        title = stringResource(R.string.live_settings_title),
                        onClose = onDismissRequest,
                    )

                    onChangePlatform?.let { change ->
                        TextButton(onClick = change) { Text(stringResource(R.string.action_change_platform)) }
                    }

                    YouTubeLiveSettingsFields(
                        settings = settings,
                        onSettingsChanged = onSettingsChanged,
                        validation = validation,
                        onValidationChanged = { validation = it },
                        serverError = serverError,
                        youtubeAccountStatus = youtubeAccountStatus,
                        hasYouTubeAccount = hasYouTubeAccount,
                        isYouTubeReconnectRequired = isYouTubeReconnectRequired,
                        isYouTubeAccountActionInProgress = isYouTubeAccountActionInProgress,
                        isYouTubeConnectEnabled = isYouTubeConnectEnabled,
                        onConnectYouTube = onConnectYouTube,
                    )

                    Button(
                        onClick = { validateForm() },
                        enabled = onPrepare == null || canPrepare,
                        modifier = Modifier
                            .fillMaxWidth()
                            .broadcastTutorialAnchor(BroadcastTutorialAnchor.START_PREPARATION),
                    ) {
                        Text(
                            stringResource(
                                if (onPrepare == null) R.string.direct_mode_done
                                else prepareActionRes,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** YouTube 방송 설정 입력 항목입니다. 설정 창과 통합 방송 설정 창이 함께 씁니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YouTubeLiveSettingsFields(
    settings: BroadcastSettings,
    onSettingsChanged: (BroadcastSettings) -> Unit,
    validation: YouTubeLiveSettingsValidation,
    onValidationChanged: (YouTubeLiveSettingsValidation) -> Unit,
    serverError: ServerErrorGuidance?,
    youtubeAccountStatus: String,
    hasYouTubeAccount: Boolean,
    isYouTubeReconnectRequired: Boolean,
    isYouTubeAccountActionInProgress: Boolean,
    isYouTubeConnectEnabled: Boolean,
    onConnectYouTube: () -> Unit,
) {
    var isPrivacyMenuExpanded by remember { mutableStateOf(false) }
    var isAudienceMenuExpanded by remember { mutableStateOf(false) }
    fun fieldError(field: String) = serverError?.takeIf { it.field == field }?.message
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (serverError != null && serverError.field !in setOf("title", "description", "privacy", "made_for_kids")) {
            Text(serverError.message.asString(), color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = settings.title,
            onValueChange = { value ->
                onValidationChanged(validation.copy(titleError = false))
                onSettingsChanged(settings.copy(title = value.take(MAX_YOUTUBE_TITLE_LENGTH)))
            },
            label = {
                Text(
                    stringResource(R.string.label_broadcast_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            singleLine = true,
            isError = validation.titleError || fieldError("title") != null,
            supportingText = if (validation.titleError || fieldError("title") != null) {
                {
                    Text(
                        fieldError("title")?.asString() ?: stringResource(R.string.validation_broadcast_title),
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
                onValidationChanged(validation.copy(descriptionError = false))
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
            isError = validation.descriptionError || fieldError("description") != null,
            supportingText = if (validation.descriptionError || fieldError("description") != null) {
                {
                    Text(
                        fieldError("description")?.asString() ?: stringResource(R.string.validation_broadcast_description),
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
                isError = fieldError("privacy") != null,
                supportingText = fieldError("privacy")?.let { message -> { Text(message.asString()) } },
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
                isError = validation.audienceError || fieldError("made_for_kids") != null,
                label = {
                    Text(
                        stringResource(R.string.label_made_for_kids),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingText = if (validation.audienceError || fieldError("made_for_kids") != null) {
                    {
                        Text(
                            fieldError("made_for_kids")?.asString() ?: stringResource(R.string.validation_audience),
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
                        onValidationChanged(validation.copy(audienceError = false))
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
                        onValidationChanged(validation.copy(audienceError = false))
                        isAudienceMenuExpanded = false
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .broadcastTutorialAnchor(BroadcastTutorialAnchor.CONNECT_ACCOUNT)
                .fillMaxWidth()
                .padding(0.dp, 8.dp),
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
                Button(
                    onClick = onConnectYouTube,
                    enabled = isYouTubeConnectEnabled && !isYouTubeAccountActionInProgress,
                ) {
                    Text(
                        text = stringResource(
                            when {
                                isYouTubeReconnectRequired -> R.string.action_reconnect
                                hasYouTubeAccount -> R.string.action_change_youtube_account
                                else -> R.string.action_connect
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
