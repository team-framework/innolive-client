package com.framework.innolive.feature.live

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framework.innolive.R
import com.framework.innolive.feature.settings.PlanMode
import com.framework.innolive.feature.settings.PlanUsage
import kotlinx.coroutines.delay

internal fun displayedRemainingTime(active: Boolean, snapshot: SessionSnapshot?, usage: PlanUsage?): BroadcastRemainingTime {
    if (active && snapshot?.remainingTime is BroadcastRemainingTime.Seconds) return snapshot.remainingTime
    val mode = PlanMode.current(snapshot?.broadcastResolution, snapshot?.visibleTargets?.size ?: 1)
    if (usage == null || !usage.isAllowed(mode)) return BroadcastRemainingTime.Unknown
    return usage.previewRemaining(mode)?.let(BroadcastRemainingTime::Seconds)
        ?: if (!active || snapshot?.remainingTime == BroadcastRemainingTime.UnlimitedOrInactive) {
            BroadcastRemainingTime.UnlimitedOrInactive
        } else BroadcastRemainingTime.Unknown
}

/** 남은 시간도 방송 시간과 같은 hh:mm:ss로 표시한다. 24시간 이상은 시간 자리가 그대로 늘어난다. */
internal fun formatRemainingSeconds(seconds: Long): String = formatBroadcastDuration(seconds * 1_000L)

/**
 * 서버는 남은 시간을 약 15초마다 갱신하므로, 방송 중에는 마지막 서버 값에서 1초씩 줄여 보여 준다.
 * 일시 중지나 갱신 지연처럼 서버 값이 줄지 않는 동안에는 줄이지 않고, 오래 갱신이 없으면 더 줄이지 않는다.
 */
internal fun countdownRemainingSeconds(serverSeconds: Long, elapsedSinceUpdateMillis: Long, counting: Boolean): Long {
    if (!counting) return serverSeconds
    val elapsedSeconds = elapsedSinceUpdateMillis.coerceIn(0L, REMAINING_COUNTDOWN_LIMIT_MILLIS) / 1_000L
    return (serverSeconds - elapsedSeconds).coerceAtLeast(0L)
}

private const val REMAINING_COUNTDOWN_LIMIT_MILLIS = 30_000L

@Composable
internal fun rememberRemainingCountdown(remaining: BroadcastRemainingTime, counting: Boolean): BroadcastRemainingTime {
    val serverSeconds = (remaining as? BroadcastRemainingTime.Seconds)?.value ?: return remaining
    // 같은 서버 값이 여러 번 와도 처음 받은 시각을 기준으로 줄인다.
    val receivedAt = remember(serverSeconds) { SystemClock.elapsedRealtime() }
    var elapsedMillis by remember(serverSeconds) { mutableLongStateOf(0L) }
    LaunchedEffect(serverSeconds, counting) {
        while (counting) {
            elapsedMillis = SystemClock.elapsedRealtime() - receivedAt
            delay(1_000L - elapsedMillis % 1_000L)
        }
    }
    return BroadcastRemainingTime.Seconds(countdownRemainingSeconds(serverSeconds, elapsedMillis, counting))
}

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
        is BroadcastRemainingTime.Seconds -> formatRemainingSeconds(remaining.value)
    }
    val uptimeDescription = stringResource(R.string.content_description_broadcast_duration, uptime)
    val remainingDescription = stringResource(R.string.plan_remaining_description, remainingText)
    // 구분선이 화면 정중앙에 오도록 양쪽을 같은 너비로 나누고, 방송 시간은 오른쪽 끝·남은 시간은 왼쪽 끝에 붙인다.
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.weight(1f).heightIn(min = TimeRowHeight), contentAlignment = Alignment.CenterEnd) {
            Text(uptime, color = Color.White, style = TimeTextStyle,
                modifier = Modifier.semantics { contentDescription = uptimeDescription })
        }
        Box(
            modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = TimeRowHeight),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.width(1.dp).height(18.dp).background(RemainingColor))
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            // 누르는 영역은 48dp를 확보하되 글자는 구분선 쪽에 붙인다.
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = TimeRowHeight, minHeight = TimeRowHeight)
                    .clickable(role = Role.Button, onClick = onOpenPlan)
                    .semantics { contentDescription = remainingDescription },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(remainingText, color = RemainingColor, style = TimeTextStyle)
            }
            if (isStale) Text(stringResource(R.string.plan_session_stale), color = RemainingColor, fontSize = 12.sp)
        }
    }
}

private val TimeRowHeight = 48.dp
private val RemainingColor = Color(0xFFB5B5B5)

// 숫자 폭을 고정해 1초마다 바뀌어도 글자가 좌우로 흔들리지 않게 한다.
private val TimeTextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
