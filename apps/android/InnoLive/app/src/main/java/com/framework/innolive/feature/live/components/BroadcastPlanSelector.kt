package com.framework.innolive.feature.live.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
 * 방송 화질과 송출 플랫폼을 고릅니다. 방송 설정 창 위쪽에 들어갑니다. 선택은 바로 [onPlanChanged]로 적용하고,
 * 막힌 선택지와 요금제 안내는 [state]가 정합니다. 계정 연결 안내는 [accountChecking]이 끝나고
 * 잠시 기다린 뒤에만 보여 줘, 창을 열자마자 안내가 나왔다 사라지지 않게 합니다. 조회 실패처럼
 * 연결 여부를 모르는 경우는 [accountIssue]의 사유를 대신 보여 줍니다.
 * 서버 송출은 화질을 고르지 않으므로 [showResolution]을 끕니다.
 */
@Composable
internal fun BroadcastPlanSelector(
    state: DirectBroadcastModeState,
    onPlanChanged: (DirectBroadcastPlan) -> Unit,
    onOpenPlan: () -> Unit,
    showResolution: Boolean = true,
    accountChecking: (BroadcastProvider) -> Boolean = { false },
    accountIssue: (BroadcastProvider) -> UiText? = { null },
    /** 계정이 없는 플랫폼은 고를 수 없으므로 같은 줄에서 바로 연결할 수 있게 합니다. */
    onConnectAccount: ((BroadcastProvider) -> Unit)? = null,
    canConnectAccount: (BroadcastProvider) -> Boolean = { true },
) {
    var accountGraceElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ACCOUNT_CAPTION_GRACE_MILLIS)
        accountGraceElapsed = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showResolution) {
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
                val showAccountCaption = accountGraceElapsed && !accountChecking(option.provider)
                Checkbox(checked = option.selected, onCheckedChange = null, enabled = option.enabled)
                Column(modifier = Modifier.weight(1f)) {
                    Text(option.provider.title, style = MaterialTheme.typography.bodyLarge)
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
                if (onConnectAccount != null && !option.accountConnected && showAccountCaption && !state.locked) {
                    TextButton(
                        onClick = { onConnectAccount(option.provider) },
                        enabled = canConnectAccount(option.provider),
                    ) {
                        Text(stringResource(R.string.action_connect))
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
        if (state.showsPlanGuidance && !state.locked) {
            TextButton(onClick = onOpenPlan) { Text(stringResource(R.string.direct_mode_open_plan)) }
        }
    }
}

/**
 * 방송 설정 메뉴의 "화질 및 플랫폼" 창입니다. 선택은 바로 적용되고 완료로 메뉴에 돌아갑니다.
 * 서버 송출은 화질을 고르지 않으므로 [showResolution]을 끕니다.
 */
@Composable
internal fun BroadcastModeDialog(
    state: DirectBroadcastModeState,
    onPlanChanged: (DirectBroadcastPlan) -> Unit,
    onOpenPlan: () -> Unit,
    onDismiss: () -> Unit,
    showResolution: Boolean = true,
    accountChecking: (BroadcastProvider) -> Boolean = { false },
    accountIssue: (BroadcastProvider) -> UiText? = { null },
    onConnectAccount: ((BroadcastProvider) -> Unit)? = null,
    canConnectAccount: (BroadcastProvider) -> Boolean = { true },
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.cappedDialogWidth(400.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(if (showResolution) R.string.broadcast_menu_mode else R.string.direct_mode_targets_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                BroadcastPlanSelector(
                    state = state,
                    onPlanChanged = onPlanChanged,
                    onOpenPlan = onOpenPlan,
                    showResolution = showResolution,
                    accountChecking = accountChecking,
                    accountIssue = accountIssue,
                    onConnectAccount = onConnectAccount,
                    canConnectAccount = canConnectAccount,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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

/** 방송 설정 메뉴와 송출 플랫폼 선택에 함께 쓰는 플랫폼 로고입니다. */
@get:DrawableRes
internal val BroadcastProvider.logoRes: Int
    get() = when (this) {
        BroadcastProvider.YOUTUBE -> R.drawable.ic_youtube
        BroadcastProvider.CHZZK -> R.drawable.ic_chzzk
    }
