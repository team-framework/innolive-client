package com.framework.innolive.feature.live

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.framework.innolive.R

internal data class AnonymizationControlsState(
    val label: String,
    val selectedEnabled: Boolean?,
    val canChange: Boolean,
)

internal fun anonymizationControlsState(
    connection: WebRtcConnectionState,
    confirmed: AnonymizationState,
    selected: Boolean,
    loaded: Boolean,
    change: AnonymizationChange,
): AnonymizationControlsState {
    val connected = connection == WebRtcConnectionState.CONNECTED
    val connecting = connection == WebRtcConnectionState.CONNECTING
    val changing = change.status == AnonymizationChangeStatus.CHANGING
    val value = if (connected) when (confirmed) {
        AnonymizationState.ENABLED -> true
        AnonymizationState.DISABLED -> false
        AnonymizationState.UNKNOWN -> null
    } else if (loaded) selected else null
    return AnonymizationControlsState(
        label = when {
            !loaded -> "선택 불러오는 중"
            changing -> "비식별화 변경 중"
            connecting -> "비식별화 ${if (selected) "On" else "Off"}로 연결 중"
            connected && value == null -> "비식별화 상태 확인 필요"
            connected -> "비식별화 ${if (value == true) "On" else "Off"}"
            else -> "연결 시 비식별화 ${if (selected) "On" else "Off"}"
        },
        selectedEnabled = value,
        canChange = loaded && !connecting && !changing,
    )
}

@Composable
internal fun AnonymizationControls(
    state: AnonymizationControlsState,
    onSelect: (Boolean) -> Unit,
) {
    val description = when (state.selectedEnabled) {
        true -> "비식별화 비활성화"
        false -> "비식별화 활성화"
        null -> "비식별화 상태 확인 필요. 활성화"
    }
    IconButton(
        enabled = state.canChange,
        onClick = { onSelect(state.selectedEnabled != true) },
        modifier = Modifier.semantics { stateDescription = state.label; contentDescription = description },
    ) {
        if (!state.canChange) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        } else if (state.selectedEnabled == null) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        } else {
            Icon(
                painter = painterResource(if (state.selectedEnabled) R.drawable.blur_enabled else R.drawable.blur_disabled),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
