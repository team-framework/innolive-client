package com.framework.innolive.feature.settings

import com.framework.innolive.ui.theme.InnoLiveAlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.ui.text.asString
import java.util.Locale

internal fun formatPlanDuration(seconds: Long): String = String.format(
    Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60,
)

@Composable
internal fun planTimeText(seconds: Long?): String =
    seconds?.let(::formatPlanDuration) ?: stringResource(R.string.plan_unlimited)

@Composable
internal fun PlanUsageSettings(props: SettingsScreenProps) {
    var lockedMode by remember(props.profileEmail) { mutableStateOf<PlanMode?>(null) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            TextButton(onClick = props.onRefreshPlan, enabled = !props.isPlanLoading) {
                Text(stringResource(if (props.isPlanLoading) R.string.plan_loading else R.string.plan_refresh))
            }
        }
        props.planError?.let {
            Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (props.planUsage != null) Text(stringResource(R.string.plan_last_value), style = PlanCaptionStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val usage = props.planUsage
        if (usage == null) {
            Text(stringResource(R.string.plan_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            PlanSummaryCard(usage)
            // 자주 보지 않는 세부 시간은 접어 두고, 필요할 때 펼쳐 봅니다.
            SettingsAccordionHeader(
                label = stringResource(R.string.plan_details),
                expanded = detailsExpanded,
                onToggle = { detailsExpanded = !detailsExpanded },
            )
            AnimatedVisibility(
                visible = detailsExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlanValueRow(stringResource(R.string.plan_label_used), planTimeText(usage.usedSeconds))
                    PlanValueRow(stringResource(R.string.plan_label_monthly),
                        planTimeText(usage.monthlySeconds.takeIf { it > 0 }))
                    PlanValueRow(stringResource(R.string.plan_label_broadcast),
                        planTimeText(usage.maxBroadcastSeconds.takeIf { it > 0 }))
                    SettingsSectionLabel(stringResource(R.string.plan_label_modes), Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.plan_mode_time_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PlanMode.entries.forEach { mode -> PlanModeRow(usage, mode, onLockedClick = { lockedMode = mode }) }
                }
            }
        }
    }
    lockedMode?.let { mode ->
        InnoLiveAlertDialog(
            onDismissRequest = { lockedMode = null },
            title = { Text(stringResource(R.string.plan_title)) },
            text = { Text(stringResource(R.string.plan_mode_locked, stringResource(mode.titleResource()))) },
            confirmButton = { TextButton(onClick = { lockedMode = null }) { Text(stringResource(R.string.action_confirm)) } },
        )
    }
}

/** 가장 자주 보는 남은 시간을 크게 보여 주고, 월 한도가 있으면 쓴 비율을 막대로 함께 보여 줍니다. */
@Composable
private fun PlanSummaryCard(usage: PlanUsage) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.plan_label_current), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                usage.plan.replaceFirstChar { it.uppercaseChar() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.plan_label_remaining), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                planTimeText(usage.remainingSeconds),
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (usage.monthlySeconds > 0) {
            LinearProgressIndicator(
                progress = { (usage.usedSeconds.toFloat() / usage.monthlySeconds).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/** 설정의 다른 줄처럼 이름은 왼쪽, 값은 오른쪽에 두고 숫자 폭을 맞춰 세로로 줄을 맞춥니다. */
@Composable
private fun PlanValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlanModeRow(usage: PlanUsage, mode: PlanMode, onLockedClick: () -> Unit) {
    val allowed = usage.isAllowed(mode)
    val availability = usage.availability(mode)
    val dimmed = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !allowed, role = Role.Button, onClick = onLockedClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(mode.titleResource()), style = MaterialTheme.typography.bodyLarge,
                color = if (allowed) MaterialTheme.colorScheme.onSurface else dimmed)
            Text(
                stringResource(R.string.plan_multiplier, availability?.multiplier?.toString() ?: "—"),
                style = PlanCaptionStyle(),
                color = dimmed,
            )
        }
        if (allowed) {
            Text(
                if (availability?.seconds == 0L) stringResource(R.string.plan_exhausted)
                else planTimeText(availability?.seconds),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = dimmed, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.plan_locked), style = MaterialTheme.typography.bodyMedium, color = dimmed)
            }
        }
    }
}

@Composable
private fun PlanCaptionStyle() = MaterialTheme.typography.bodyMedium

private fun PlanMode.titleResource(): Int = when (this) {
    PlanMode.HD_SINGLE -> R.string.plan_hd_single
    PlanMode.FHD_SINGLE -> R.string.plan_fhd_single
    PlanMode.HD_MULTI -> R.string.plan_hd_multi
    PlanMode.FHD_MULTI -> R.string.plan_fhd_multi
}
