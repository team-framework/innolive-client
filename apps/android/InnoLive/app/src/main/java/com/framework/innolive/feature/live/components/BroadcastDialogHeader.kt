package com.framework.innolive.feature.live.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.framework.innolive.R

/** 방송 설정 메뉴와 하위 설정 창이 함께 쓰는 제목 줄입니다. 오른쪽 X로 창을 닫습니다. */
@Composable
internal fun BroadcastDialogHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
        }
    }
}
