package com.framework.innolive.feature.settings.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastVideoQualitySettings
import com.framework.innolive.feature.live.VideoQualityCaptureState
import com.framework.innolive.feature.live.VideoStabilizationStatus

/** 고른 손떨림 보정 값과 카메라가 알려 준 실제 적용 상태를 함께 보여 줍니다. */
@Composable
internal fun CameraStabilizationControl(
    settings: BroadcastVideoQualitySettings,
    captureState: VideoQualityCaptureState,
    onSettingsChanged: (BroadcastVideoQualitySettings) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val stabilizationEnabled = settings.stabilizationEnabled
    val status = when (captureState.stabilizationStatus) {
        VideoStabilizationStatus.ACTIVE -> R.string.video_stabilization_active
        VideoStabilizationStatus.UNSUPPORTED -> R.string.video_stabilization_unsupported
        VideoStabilizationStatus.PENDING -> R.string.video_stabilization_pending
        VideoStabilizationStatus.INACTIVE ->
            if (stabilizationEnabled) R.string.video_stabilization_inactive else R.string.video_stabilization_off
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = stabilizationEnabled, enabled = enabled, role = Role.Switch) {
                onSettingsChanged(settings.copy(stabilizationEnabled = it))
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.video_stabilization), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = stabilizationEnabled, onCheckedChange = null, enabled = enabled)
    }
}
