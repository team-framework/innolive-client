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
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString

internal data class AnonymizationControlsState(
    val label: UiText,
    val actionDescription: UiText,
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
    val selectedLabel = UiText.Resource(
        if (selected) R.string.anonymization_on else R.string.anonymization_off,
    )
    val confirmedLabel = UiText.Resource(
        if (value == true) R.string.anonymization_on else R.string.anonymization_off,
    )
    return AnonymizationControlsState(
        label = when {
            !loaded -> UiText.Resource(R.string.anonymization_loading)
            changing -> UiText.Resource(R.string.anonymization_changing)
            connecting -> UiText.Resource(R.string.anonymization_connecting, listOf(selectedLabel))
            connected && value == null -> UiText.Resource(R.string.anonymization_needs_check)
            connected -> UiText.Resource(R.string.anonymization_current, listOf(confirmedLabel))
            else -> UiText.Resource(R.string.anonymization_when_connected, listOf(selectedLabel))
        },
        actionDescription = when (value) {
            true -> UiText.Resource(R.string.anonymization_disable)
            false -> UiText.Resource(R.string.anonymization_enable)
            null -> UiText.Resource(R.string.anonymization_enable)
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
    val localizedStateDescription = state.label.asString()
    val localizedActionDescription = state.actionDescription.asString()
    IconButton(
        enabled = state.canChange,
        onClick = { onSelect(state.selectedEnabled != true) },
        modifier = Modifier.semantics {
            stateDescription = localizedStateDescription
            contentDescription = localizedActionDescription
        },
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
