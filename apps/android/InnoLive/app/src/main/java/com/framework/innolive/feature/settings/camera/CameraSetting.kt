package com.framework.innolive.feature.settings.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.framework.innolive.R
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
        Dropdown(
            label = stringResource(R.string.label_audio_device),
            selectedOption = props.selectedAudioDevice,
            onClick = props.onOpenAudioDeviceOptions,
        )
    }
}
