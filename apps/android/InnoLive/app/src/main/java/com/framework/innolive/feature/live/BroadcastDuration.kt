package com.framework.innolive.feature.live

import java.util.Locale

internal fun nextBroadcastStartedAt(
    currentStartedAtMillis: Long?,
    state: BroadcastState,
    nowMillis: Long,
): Long? = when (state) {
    BroadcastState.LIVE -> currentStartedAtMillis ?: nowMillis
    BroadcastState.PAUSING,
    BroadcastState.PAUSED,
    BroadcastState.RESUMING,
    BroadcastState.STOPPING -> currentStartedAtMillis
    else -> null
}

internal fun formatBroadcastDuration(elapsedMillis: Long): String {
    val elapsedSeconds = elapsedMillis.coerceAtLeast(0L) / 1_000L
    val hours = elapsedSeconds / 3_600L
    val minutes = elapsedSeconds % 3_600L / 60L
    val seconds = elapsedSeconds % 60L
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}
