package com.framework.innolive.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.Role
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
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = props.onRefreshPlan, enabled = !props.isPlanLoading) {
                Text(stringResource(if (props.isPlanLoading) R.string.plan_loading else R.string.plan_refresh))
            }
        }
        props.planError?.let {
            Text(it.asString(), color = MaterialTheme.colorScheme.error)
            if (props.planUsage != null) Text(stringResource(R.string.plan_last_value))
        }
        val usage = props.planUsage
        if (usage == null) {
            Text(stringResource(R.string.plan_waiting))
        } else {
            Text(stringResource(R.string.plan_current, usage.plan.replaceFirstChar { it.uppercaseChar() }))
            Text(stringResource(R.string.plan_monthly_limit, planTimeText(usage.monthlySeconds.takeIf { it > 0 })))
            Text(stringResource(R.string.plan_broadcast_limit, planTimeText(usage.maxBroadcastSeconds.takeIf { it > 0 })))
            Text(stringResource(R.string.plan_used, planTimeText(usage.usedSeconds)))
            Text(stringResource(R.string.plan_remaining, planTimeText(usage.remainingSeconds)))
            Text(stringResource(R.string.plan_mode_time_hint), style = MaterialTheme.typography.bodySmall)
            PlanMode.entries.forEach { mode ->
                val allowed = usage.isAllowed(mode)
                val availability = usage.availability(mode)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !allowed, role = Role.Button) { lockedMode = mode }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!allowed) Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.plan_locked))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(mode.titleResource()))
                        Text(
                            stringResource(R.string.plan_multiplier, availability?.multiplier?.toString() ?: "—"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(when {
                        !allowed -> stringResource(R.string.plan_locked)
                        availability?.seconds == 0L -> stringResource(R.string.plan_exhausted)
                        else -> planTimeText(availability?.seconds)
                    })
                }
            }
        }
    }
    lockedMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { lockedMode = null },
            title = { Text(stringResource(R.string.plan_title)) },
            text = { Text(stringResource(R.string.plan_mode_locked, stringResource(mode.titleResource()))) },
            confirmButton = { TextButton(onClick = { lockedMode = null }) { Text(stringResource(R.string.action_confirm)) } },
        )
    }
}

private fun PlanMode.titleResource(): Int = when (this) {
    PlanMode.HD_SINGLE -> R.string.plan_hd_single
    PlanMode.FHD_SINGLE -> R.string.plan_fhd_single
    PlanMode.HD_MULTI -> R.string.plan_hd_multi
    PlanMode.FHD_MULTI -> R.string.plan_fhd_multi
}
