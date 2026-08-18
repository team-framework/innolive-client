package com.framework.innolive.feature.settings.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.framework.innolive.feature.settings.components.Dropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSetting(props: CameraSettingProps) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = "카메라 및 오디오 설정") },
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

        Dropdown(
            label = "카메라 해상도",
            selectedOption = props.selectedResolution,
            onClick = props.onOpenResolutionOptions,
        )
        Dropdown(
            label = "카메라 기기",
            selectedOption = props.selectedCameraDevice,
            onClick = props.onOpenCameraDeviceOptions,
        )
        Dropdown(
            label = "오디오 기기",
            selectedOption = props.selectedAudioDevice,
            onClick = props.onOpenAudioDeviceOptions,
        )
    }
}
