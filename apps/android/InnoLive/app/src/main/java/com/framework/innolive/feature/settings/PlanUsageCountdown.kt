package com.framework.innolive.feature.settings

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
internal fun rememberPlanChargeMillis(account: String?, charging: Boolean, multiplier: Long): Long {
    var chargedMillis by remember(account) { mutableLongStateOf(0L) }
    LaunchedEffect(account, charging, multiplier) {
        if (!charging || multiplier <= 0) return@LaunchedEffect
        var lastTick = SystemClock.elapsedRealtime()
        fun recordElapsed() {
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - lastTick).coerceAtLeast(0L)
            chargedMillis += minOf(elapsed, (Long.MAX_VALUE - chargedMillis) / multiplier) * multiplier
            lastTick = now
        }
        try {
            while (true) {
                delay(1_000L)
                recordElapsed()
            }
        } finally {
            recordElapsed()
        }
    }
    return chargedMillis
}
