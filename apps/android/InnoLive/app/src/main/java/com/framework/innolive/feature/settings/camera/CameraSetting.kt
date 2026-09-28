package com.framework.innolive.feature.settings.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.framework.innolive.R
import com.framework.innolive.feature.live.VideoStabilizationStatus
import com.framework.innolive.feature.settings.components.Dropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSetting(props: CameraSettingProps) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text(text = stringResource(R.string.settings_camera_audio)) },
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
            label = stringResource(R.string.label_camera_resolution),
            selectedOption = props.selectedResolution,
            onClick = props.onOpenResolutionOptions,
        )
        Dropdown(
            label = stringResource(R.string.label_camera_device),
            selectedOption = props.selectedCameraDevice,
            onClick = props.onOpenCameraDeviceOptions,
        )
        CameraStabilizationControl(props)

        Dropdown(
            label = stringResource(R.string.label_audio_device),
            selectedOption = props.selectedAudioDevice,
            onClick = props.onOpenAudioDeviceOptions,
        )
    }
}

@Composable
internal fun CameraStabilizationControl(props: CameraSettingProps) {
    val enabled = props.videoQualitySettings.stabilizationEnabled
    val status = when (props.videoQualityCaptureState.stabilizationStatus) {
        VideoStabilizationStatus.ACTIVE -> R.string.video_stabilization_active
        VideoStabilizationStatus.UNSUPPORTED -> R.string.video_stabilization_unsupported
        VideoStabilizationStatus.PENDING -> R.string.video_stabilization_pending
        VideoStabilizationStatus.INACTIVE -> if (enabled) R.string.video_stabilization_inactive else R.string.video_stabilization_off
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch) {
                props.onVideoQualitySettingsChanged(props.videoQualitySettings.copy(stabilizationEnabled = it))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.video_stabilization), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}
