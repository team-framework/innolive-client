package com.framework.innolive.feature.settings.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
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
            title = { Text(text = "방송 설정") },
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
            label = "방송 플랫폼",
            selectedOption = props.selectedPlatform,
            onClick = props.onOpenPlatformOptions,
        )

        OutlinedTextField(
            value = props.title,
            onValueChange = props.onTitleChanged,
            label = { Text(text = "방송 제목") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        OutlinedTextField(
            value = props.description,
            onValueChange = props.onDescriptionChanged,
            label = { Text(text = "방송 설명") },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        Dropdown(
            label = "공개 범위",
            selectedOption = props.selectedPrivacy,
            onClick = props.onOpenPrivacyOptions,
        )

        Dropdown(
            label = "아동용 콘텐츠",
            selectedOption = props.selectedAudience,
            onClick = props.onOpenAudienceOptions,
        )

        OutlinedTextField(
            value = props.categoryId,
            onValueChange = props.onCategoryIdChanged,
            label = { Text(text = "YouTube 카테고리 ID (선택)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "방송 계정",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = {},
                enabled = false,
            ) {
                Text(text = "연동")
            }
        }

        Button(
            onClick = props.onSave,
            enabled = props.isSaveEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(text = "방송 설정 저장")
        }

        Text(
            text = props.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}
