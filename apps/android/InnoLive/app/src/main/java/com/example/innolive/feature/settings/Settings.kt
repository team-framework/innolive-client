package com.example.innolive.feature.settings

import android.graphics.drawable.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.VideoCameraBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class SettingsMenuItem(
    val icon: ImageVector,
    val label: String,
    val onNav: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(props: SettingsScreenProps) {
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
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 0.dp),
        ) {
            settingItems.forEach { item ->
                Button(
                    onClick = item.onNav
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
        }
    }
}
