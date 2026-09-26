package com.framework.innolive.feature.live

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import java.text.NumberFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastVideoControls(
    settings: BroadcastVideoQualitySettings,
    captureState: VideoQualityCaptureState,
    previews: Map<VideoLookPreset, Bitmap>?,
    onSettingsChanged: (BroadcastVideoQualitySettings) -> Unit,
    onDismiss: () -> Unit,
    mirrorPreviews: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.video_controls_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                }
            }
            BroadcastVideoAdjustments(settings, captureState, previews, onSettingsChanged, mirrorPreviews)
        }
    }
}

@Composable
internal fun BroadcastVideoAdjustments(
    settings: BroadcastVideoQualitySettings,
    captureState: VideoQualityCaptureState,
    previews: Map<VideoLookPreset, Bitmap>?,
    onSettingsChanged: (BroadcastVideoQualitySettings) -> Unit,
    mirrorPreviews: Boolean = false,
) {
    val normalized = settings.normalized()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.video_presets),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VideoLookPreset.entries.forEach { preset ->
                VideoPresetCard(
                    preset = preset,
                    selected = preset.matches(normalized),
                    preview = previews?.get(preset),
                    mirrorPreview = mirrorPreviews,
                    onClick = { onSettingsChanged(preset.applyTo(normalized)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (previews.isNullOrEmpty()) {
            Text(
                text = stringResource(R.string.video_preview_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val minExposure = captureState.minExposureEV.coerceIn(-2f, 2f)
        val maxExposure = captureState.maxExposureEV.coerceIn(minExposure, 2f)
        val exposureEnabled = captureState.exposureSupported && minExposure < maxExposure
        VideoAdjustmentSlider(
            title = stringResource(R.string.video_exposure),
            value = if (exposureEnabled) normalized.exposureEV.coerceIn(minExposure, maxExposure) else 0f,
            valueLabel = stringResource(R.string.video_exposure_value, signedNumber(captureState.appliedExposureEV, 1)),
            range = if (exposureEnabled) minExposure..maxExposure else -2f..2f,
            leading = stringResource(R.string.video_darker),
            trailing = stringResource(R.string.video_brighter),
            enabled = exposureEnabled,
            onValueChange = { onSettingsChanged(normalized.copy(exposureEV = it)) },
        )
        if (!exposureEnabled) {
            Text(
                text = stringResource(R.string.video_exposure_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VideoAdjustmentSlider(
            title = stringResource(R.string.video_warmth),
            value = normalized.warmth,
            valueLabel = signedNumber(normalized.warmth, 2),
            range = -1f..1f,
            leading = stringResource(R.string.video_cooler),
            trailing = stringResource(R.string.video_warmer),
            steps = 39,
            onValueChange = { onSettingsChanged(normalized.copy(warmth = it)) },
        )
        VideoAdjustmentSlider(
            title = stringResource(R.string.video_saturation),
            value = normalized.saturation,
            valueLabel = stringResource(R.string.video_percent_value, (normalized.saturation * 100).roundToInt()),
            range = 0f..2f,
            leading = stringResource(R.string.video_percent_value, 0),
            trailing = stringResource(R.string.video_percent_value, 200),
            steps = 39,
            onValueChange = { onSettingsChanged(normalized.copy(saturation = it)) },
        )
        OutlinedButton(
            onClick = { onSettingsChanged(normalized.resetAdjustments()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.video_reset), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun VideoPresetCard(
    preset: VideoLookPreset,
    selected: Boolean,
    preview: Bitmap?,
    mirrorPreview: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(when (preset) {
        VideoLookPreset.VIVID -> R.string.video_preset_vivid
        VideoLookPreset.BRIGHT -> R.string.video_preset_bright
        VideoLookPreset.WARM -> R.string.video_preset_warm
    })
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize().graphicsLayer {
                            scaleX = if (mirrorPreview) -1f else 1f
                        },
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.Outlined.Videocam, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun VideoAdjustmentSlider(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    leading: String,
    trailing: String,
    enabled: Boolean = true,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            steps = steps,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = valueLabel
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(leading, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun signedNumber(value: Float, digits: Int): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = digits
    maximumFractionDigits = digits
}.format(value).let { if (value > 0) "+$it" else it }
