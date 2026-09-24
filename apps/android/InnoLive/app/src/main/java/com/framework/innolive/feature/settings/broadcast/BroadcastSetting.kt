package com.framework.innolive.feature.settings.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.settings.components.Dropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastSetting(props: BroadcastSettingProps) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text(text = stringResource(R.string.broadcast_settings_title)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = props.onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
        )

        Dropdown(
            label = stringResource(R.string.label_broadcast_platform),
            selectedOption = props.selectedPlatform,
            onClick = props.onOpenPlatformOptions,
        )

        OutlinedTextField(
            value = props.title,
            onValueChange = props.onTitleChanged,
            label = { Text(text = stringResource(R.string.label_broadcast_title)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        OutlinedTextField(
            value = props.description,
            onValueChange = props.onDescriptionChanged,
            label = { Text(text = stringResource(R.string.label_broadcast_description)) },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        Dropdown(
            label = stringResource(R.string.label_broadcast_privacy),
            selectedOption = props.selectedPrivacy,
            onClick = props.onOpenPrivacyOptions,
        )

        Dropdown(
            label = stringResource(R.string.label_made_for_kids),
            selectedOption = props.selectedAudience,
            onClick = props.onOpenAudienceOptions,
        )

        OutlinedTextField(
            value = props.categoryId,
            onValueChange = props.onCategoryIdChanged,
            label = { Text(text = stringResource(R.string.label_youtube_category)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.label_broadcast_account),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!props.youtubeChannelTitle.isNullOrBlank()) {
                Text(
                    text = props.youtubeChannelTitle,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!props.hasVerifiedYouTubeAccount || props.isYouTubeReconnectRequired) {
                val connectDisabledReason = props.connectDisabledReasonRes?.let { stringResource(it) }
                Button(
                    onClick = props.onConnectYouTube,
                    enabled = props.isYouTubeConnectEnabled && !props.isYouTubeAccountActionInProgress,
                    modifier = Modifier
                        .align(Alignment.End)
                        .semantics {
                            if (connectDisabledReason != null) stateDescription = connectDisabledReason
                        },
                ) {
                    Text(
                        text = if (
                            props.isYouTubeReconnectRequired ||
                            !props.youtubeChannelTitle.isNullOrBlank()
                        ) stringResource(R.string.action_reconnect)
                        else stringResource(R.string.action_connect),
                    )
                }
            }
        }

        Text(
            text = props.youtubeAccountStatus,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )

        val saveDisabledReason = props.saveDisabledReasonRes?.let { stringResource(it) }
        Button(
            onClick = props.onSave,
            enabled = props.isSaveEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .semantics {
                    if (saveDisabledReason != null) stateDescription = saveDisabledReason
                },
        ) {
            Text(text = stringResource(R.string.action_save_broadcast_settings))
        }

        Text(
            text = props.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}
