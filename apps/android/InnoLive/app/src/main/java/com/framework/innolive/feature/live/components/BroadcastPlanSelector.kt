package com.framework.innolive.feature.live.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastModeState
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.DirectModeBlock
import com.framework.innolive.feature.live.direct.broadcast.DirectModeWarning
import com.framework.innolive.feature.live.direct.broadcast.labelRes
import com.framework.innolive.feature.live.direct.broadcast.selectResolution
import com.framework.innolive.feature.live.direct.broadcast.title
import com.framework.innolive.feature.live.direct.broadcast.toggleProvider
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString
import kotlinx.coroutines.delay

/**
 * 직접 송출의 화질과 송출 플랫폼을 고릅니다(iOS `BroadcastModeView`). 선택은 바로 [onPlanChanged]로 적용하고,
 * 막힌 선택지와 요금제 안내는 [state]가 정합니다. 계정 연결 안내는 [accountChecking]이 끝나고
 * 잠시 기다린 뒤에만 보여 줘, 창을 열자마자 안내가 나왔다 사라지지 않게 합니다. 조회 실패처럼
 * 연결 여부를 모르는 경우는 [accountIssue]의 사유를 대신 보여 줍니다.
 */
@Composable
internal fun DirectBroadcastModeDialog(
    state: DirectBroadcastModeState,
    onPlanChanged: (DirectBroadcastPlan) -> Unit,
    onOpenPlan: () -> Unit,
    onDismiss: () -> Unit,
    accountChecking: (BroadcastProvider) -> Boolean = { false },
    accountIssue: (BroadcastProvider) -> UiText? = { null },
) {
    var accountGraceElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ACCOUNT_CAPTION_GRACE_MILLIS)
        accountGraceElapsed = true
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.cappedDialogWidth(400.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.direct_mode_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.direct_mode_resolution_title), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    state.resolutions.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = option.selected,
                            onClick = { state.selectResolution(option.resolution)?.let(onPlanChanged) },
                            enabled = option.enabled || option.selected,
                            shape = SegmentedButtonDefaults.itemShape(index, state.resolutions.size),
                        ) {
                            Text(stringResource(option.resolution.labelRes))
                        }
                    }
                }
                Column {
                    Text(stringResource(R.string.direct_mode_targets_title), style = MaterialTheme.typography.titleSmall)
                    // 마지막 대상 안내는 선택할 때마다 생겼다 사라지면 창 높이가 흔들리므로 제목 아래에 늘 둡니다.
                    Text(
                        stringResource(R.string.direct_mode_last_target),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.providers.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = option.selected,
                                enabled = option.enabled,
                                role = Role.Checkbox,
                                onValueChange = { state.toggleProvider(option.provider)?.let(onPlanChanged) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = option.selected, onCheckedChange = null, enabled = option.enabled)
                        Column {
                            Text(option.provider.title, style = MaterialTheme.typography.bodyLarge)
                            val showAccountCaption = accountGraceElapsed && !accountChecking(option.provider)
                            val issue = accountIssue(option.provider)
                                ?.takeIf { showAccountCaption && !option.accountConnected }
                            val caption = issue?.asString() ?: providerCaption(
                                option.block, option.accountConnected || !showAccountCaption,
                            )?.let { stringResource(it) }
                            caption?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                val notice = when {
                    state.locked -> R.string.direct_mode_locked
                    state.warning == DirectModeWarning.NOT_IN_PLAN -> R.string.direct_mode_not_in_plan
                    state.warning == DirectModeWarning.TIME_EXHAUSTED -> R.string.direct_mode_time_exhausted
                    state.showsPlanGuidance -> R.string.direct_mode_plan_locked
                    else -> null
                }
                notice?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.warning != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (state.showsPlanGuidance && !state.locked) {
                        TextButton(onClick = onOpenPlan) { Text(stringResource(R.string.direct_mode_open_plan)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.direct_mode_done)) }
                }
            }
        }
    }
}

private fun providerCaption(block: DirectModeBlock?, accountConnected: Boolean): Int? = when {
    !accountConnected -> R.string.direct_mode_account_required
    block == DirectModeBlock.PLAN -> R.string.direct_mode_option_plan_locked
    else -> null
}

/** 계정 조회가 막 끝난 직후의 일시적인 미연결 상태를 안내로 보여 주지 않도록 기다리는 시간입니다. */
private const val ACCOUNT_CAPTION_GRACE_MILLIS = 1_500L
