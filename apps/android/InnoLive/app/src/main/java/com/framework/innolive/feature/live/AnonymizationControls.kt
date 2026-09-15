package com.framework.innolive.feature.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnonymizationControls(
    state: AnonymizationControlsState,
    connectionStatus: String,
    error: String?,
    onSelect: (Boolean) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.label, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.selectedEnabled == true,
                    enabled = state.canChange,
                    onClick = { onSelect(true) },
                    label = { Text("On") },
                )
                FilterChip(
                    selected = state.selectedEnabled == false,
                    enabled = state.canChange,
                    onClick = { onSelect(false) },
                    label = { Text("Off") },
                )
            }
            if (connectionStatus.isNotBlank()) {
                Text(connectionStatus, style = MaterialTheme.typography.labelMedium)
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                Text("On 또는 Off를 눌러 다시 시도해 주세요.", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
