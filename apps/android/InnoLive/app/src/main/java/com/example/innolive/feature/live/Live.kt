package com.example.innolive.feature.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LiveScreen(props: LiveScreenProps) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Button(
            modifier = Modifier.padding(12.dp),
            onClick = {
                props.onOpenSettings()
            }
        ) {
            Text(text="설정으로 이동")
        }
    }
}
