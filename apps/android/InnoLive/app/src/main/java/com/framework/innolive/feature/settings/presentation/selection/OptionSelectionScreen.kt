package com.framework.innolive.feature.settings.selection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionSelectionScreen(
    title: String,
    options: List<SettingOption>,
    selectedKey: String,
    onOptionSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "뒤로가기",
                    )
                }
            },
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = options,
                key = SettingOption::key,
            ) { option ->
                ListItem(
                    headlineContent = { Text(text = option.label) },
                    leadingContent = {
                        if (option.key == selectedKey) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = "선택됨",
                            )
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .clickable {
                            onOptionSelected(option.key)
                            onBack()
                        },
                )
            }
        }
    }
}
