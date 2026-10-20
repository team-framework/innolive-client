package com.framework.innolive.feature.live

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString
import kotlin.math.roundToInt

/** 영상 조절 시트의 모자이크 구역 상태입니다. [config]는 서버가 확정한 값입니다. */
@Immutable
data class MosaicControlsState(
    val config: MosaicConfig,
    val enabled: Boolean,
    val isChanging: Boolean,
    /** 조절할 수 없거나 적용되지 않는 이유입니다. */
    @param:StringRes val noteRes: Int?,
    val error: UiText?,
    val onChange: (MosaicConfig) -> Unit,
)

/**
 * 조절할 수 없거나 적용되지 않는 이유입니다. iOS와 같은 순서로 방송 준비 전, 온디바이스 처리, 비식별화 꺼짐을 봅니다.
 */
@StringRes
internal fun mosaicNoteRes(
    hasSession: Boolean,
    onDeviceProcessing: Boolean,
    canChange: Boolean,
    anonymizationEnabled: Boolean,
): Int? = when {
    !hasSession -> R.string.mosaic_prepare_required
    onDeviceProcessing -> R.string.mosaic_server_only
    canChange && !anonymizationEnabled -> R.string.mosaic_anonymization_off
    else -> null
}

private data class MosaicDraft(val blurRadius: Int, val maskMargin: Int) {
    constructor(config: MosaicConfig) : this(config.effectiveBlurRadius, config.effectiveMaskMargin)
}

/**
 * iOS 영상 조절의 모자이크 구역입니다. 슬라이더를 움직이는 동안은 화면 값만 바꾸고, 손을 떼면 서버로 보냅니다.
 * 응답이 오면 서버가 확정한 값을 보여 줍니다.
 */
@Composable
internal fun MosaicControls(state: MosaicControlsState) {
    var draft by remember { mutableStateOf<MosaicDraft?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    // 요청이 끝났거나 서버 값이 바뀌면 끌던 값을 내려놓고 확정값을 보여 줍니다.
    LaunchedEffect(state.isChanging, state.config) {
        if (!state.isChanging && !isEditing) draft = null
    }
    val shown = draft ?: MosaicDraft(state.config)
    fun commit() {
        isEditing = false
        val value = draft ?: return
        state.onChange(state.config.adjusted(blurRadius = value.blurRadius, maskMargin = value.maskMargin))
    }

    Column(modifier = Modifier.testTag("mosaic-controls"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.mosaic_title), style = MaterialTheme.typography.titleSmall)
            state.noteRes?.let { note ->
                Text(
                    stringResource(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        VideoAdjustmentSlider(
            title = stringResource(R.string.mosaic_blur_strength),
            value = shown.blurRadius.toFloat(),
            valueLabel = shown.blurRadius.toString(),
            range = MosaicConfig.BLUR_RADIUS_RANGE.first.toFloat()..MosaicConfig.BLUR_RADIUS_RANGE.last.toFloat(),
            leading = stringResource(R.string.mosaic_weaker),
            trailing = stringResource(R.string.mosaic_stronger),
            enabled = state.enabled,
            steps = MosaicConfig.BLUR_RADIUS_RANGE.last - MosaicConfig.BLUR_RADIUS_RANGE.first - 1,
            onValueChangeFinished = ::commit,
            onValueChange = {
                isEditing = true
                draft = shown.copy(blurRadius = it.roundToInt())
            },
        )
        VideoAdjustmentSlider(
            title = stringResource(R.string.mosaic_mask_margin),
            value = shown.maskMargin.toFloat(),
            valueLabel = stringResource(R.string.mosaic_pixels_value, shown.maskMargin),
            range = MosaicConfig.MASK_MARGIN_RANGE.first.toFloat()..MosaicConfig.MASK_MARGIN_RANGE.last.toFloat(),
            leading = stringResource(R.string.mosaic_narrower),
            trailing = stringResource(R.string.mosaic_wider),
            enabled = state.enabled,
            steps = MosaicConfig.MASK_MARGIN_RANGE.last - MosaicConfig.MASK_MARGIN_RANGE.first - 1,
            onValueChangeFinished = ::commit,
            onValueChange = {
                isEditing = true
                draft = shown.copy(maskMargin = it.roundToInt())
            },
        )
        state.error?.let { error ->
            Text(error.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = {
                draft = null
                state.onChange(state.config.adjusted(blurRadius = null, maskMargin = null))
            },
            enabled = state.enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isChanging) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(stringResource(R.string.mosaic_reset), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
