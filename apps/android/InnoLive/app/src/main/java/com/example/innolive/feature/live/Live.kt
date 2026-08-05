package com.example.innolive.feature.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LiveScreen(props: LiveScreenProps) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileDisplay(
            name = props.profileName,
            email = props.profileEmail,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = props.onOpenSettings) {
                Text(text = "설정으로 이동")
            }
            OutlinedButton(onClick = props.onLogout) {
                Text(text = "로그아웃")
            }
        }
    }
}
