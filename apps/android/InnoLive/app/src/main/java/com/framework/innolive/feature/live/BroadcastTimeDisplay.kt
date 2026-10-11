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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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

/**
 * 서버의 1회 한도는 세션 단위라, 같은 세션에서 다시 방송하면 이미 방송한 시간만큼 줄어 있다.
 * 세션 값이 있으면 방송 전·종료 후에도 그 값을 보여 주고, 아직 없을 때만 요금제 기준 값으로 보완한다.
 */
internal fun displayedRemainingTime(active: Boolean, snapshot: SessionSnapshot?, usage: PlanUsage?): BroadcastRemainingTime {
    if (snapshot?.remainingTime is BroadcastRemainingTime.Seconds) return snapshot.remainingTime
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
 * 서버는 남은 시간을 약 15초마다 갱신하므로, 방송 중에는 기준 값에서 1초씩 줄여 보여 준다.
 * 일시 중지하면 그때 보이던 값에서 멈추고, 재개하면 멈춘 값에서 다시 줄인다. 새 서버 값이 오면 그 값으로 맞춘다.
 * 서버 갱신이 오래 없으면 화면 값이 서버와 멀어지지 않도록 기준 시각에서 30초까지만 줄인다.
 */
internal data class RemainingCountdown(
    val serverSeconds: Long,
    val baseSeconds: Long = serverSeconds,
    val baseAtMillis: Long,
    val counting: Boolean = false,
) {
    fun displayed(nowMillis: Long): Long {
        if (!counting) return baseSeconds
        val elapsedSeconds = (nowMillis - baseAtMillis).coerceIn(0L, REMAINING_COUNTDOWN_LIMIT_MILLIS) / 1_000L
        return (baseSeconds - elapsedSeconds).coerceAtLeast(0L)
    }

    /** 같은 서버 값이 여러 번 와도 처음 받은 시각을 기준으로 줄인다. */
    fun onServerValue(seconds: Long, nowMillis: Long): RemainingCountdown =
        if (seconds == serverSeconds) this
        else copy(serverSeconds = seconds, baseSeconds = seconds, baseAtMillis = nowMillis)

    fun onCountingChanged(isCounting: Boolean, nowMillis: Long): RemainingCountdown =
        if (isCounting == counting) this
        else copy(baseSeconds = displayed(nowMillis), baseAtMillis = nowMillis, counting = isCounting)
}

private const val REMAINING_COUNTDOWN_LIMIT_MILLIS = 30_000L

@Composable
internal fun rememberRemainingCountdown(remaining: BroadcastRemainingTime, counting: Boolean): BroadcastRemainingTime {
    val serverSeconds = (remaining as? BroadcastRemainingTime.Seconds)?.value ?: return remaining
    var countdown by remember {
        mutableStateOf(RemainingCountdown(serverSeconds = serverSeconds, baseAtMillis = SystemClock.elapsedRealtime()))
    }
    // 서버 값과 송출 상태 변화를 화면 그리기 전에 반영해 값이 한 프레임이라도 튀지 않게 한다.
    val now = SystemClock.elapsedRealtime()
    val current = countdown.onServerValue(serverSeconds, now).onCountingChanged(counting, now)
    SideEffect { if (current != countdown) countdown = current }
    var tick by remember { mutableLongStateOf(now) }
    LaunchedEffect(current.baseAtMillis, current.counting) {
        while (current.counting) {
            tick = SystemClock.elapsedRealtime()
            delay(1_000L - (tick - current.baseAtMillis).mod(1_000L))
        }
    }
    return BroadcastRemainingTime.Seconds(current.displayed(maxOf(tick, current.baseAtMillis)))
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
