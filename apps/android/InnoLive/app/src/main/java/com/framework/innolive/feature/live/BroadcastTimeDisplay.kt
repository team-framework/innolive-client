package com.framework.innolive.feature.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framework.innolive.R
import com.framework.innolive.feature.settings.PlanMode
import com.framework.innolive.feature.settings.PlanUsage

internal fun displayedRemainingTime(active: Boolean, snapshot: SessionSnapshot?, usage: PlanUsage?): BroadcastRemainingTime {
    if (active) return snapshot?.remainingTime ?: BroadcastRemainingTime.Unknown
    val mode = PlanMode.current(snapshot?.broadcastResolution, snapshot?.visibleTargets?.size ?: 1)
    if (usage == null || !usage.isAllowed(mode)) return BroadcastRemainingTime.Unknown
    return usage.previewRemaining(mode)?.let(BroadcastRemainingTime::Seconds) ?: BroadcastRemainingTime.UnlimitedOrInactive
}

internal fun compactRemainingSeconds(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}min"

@Composable
internal fun BroadcastTimeDisplay(
    uptime: String,
    remaining: BroadcastRemainingTime,
    isStale: Boolean,
    onOpenPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remainingText = when (remaining) {
        BroadcastRemainingTime.Unknown -> "—"
        BroadcastRemainingTime.UnlimitedOrInactive -> stringResource(R.string.plan_unlimited)
        is BroadcastRemainingTime.Seconds -> compactRemainingSeconds(remaining.value)
    }
    val uptimeDescription = stringResource(R.string.content_description_broadcast_duration, uptime)
    val remainingDescription = stringResource(R.string.plan_remaining_description, remainingText)
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(uptime, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { contentDescription = uptimeDescription })
            Text(remainingText, color = Color(0xFFB5B5B5), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onOpenPlan)
                    .semantics { contentDescription = remainingDescription })
        }
        if (isStale) Text(stringResource(R.string.plan_session_stale), color = Color(0xFFB5B5B5), fontSize = 12.sp)
    }
}
