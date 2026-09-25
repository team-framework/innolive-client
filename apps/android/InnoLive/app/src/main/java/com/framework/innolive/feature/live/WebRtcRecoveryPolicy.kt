package com.framework.innolive.feature.live

import org.json.JSONObject

internal data class WebRtcRecoveryPolicy(
    val windowMillis: Long = 50_000,
    val debounceMillis: Long = 2_000,
    val maxAttempts: Int = 10,
)

internal fun parseWebRtcRecoveryPolicy(config: JSONObject): WebRtcRecoveryPolicy {
    val recovery = config.optJSONObject("recovery") ?: return WebRtcRecoveryPolicy()
    val defaults = WebRtcRecoveryPolicy()
    return WebRtcRecoveryPolicy(
        windowMillis = recovery.optLong("window_ms").takeIf { it > 0 } ?: defaults.windowMillis,
        debounceMillis = recovery.optLong("debounce_ms").takeIf { it >= 0 && recovery.has("debounce_ms") }
            ?: defaults.debounceMillis,
        maxAttempts = recovery.optInt("max_attempts").takeIf { it > 0 } ?: defaults.maxAttempts,
    )
}

/** Uses elapsed realtime so wall-clock changes cannot extend the server recovery window. */
internal class WebRtcRecoveryWindow(private val policy: WebRtcRecoveryPolicy) {
    var deadlineMillis: Long? = null
        private set
    var attempts: Int = 0
        private set

    fun begin(nowMillis: Long) {
        if (deadlineMillis == null) {
            deadlineMillis = nowMillis + policy.windowMillis
            attempts = 0
        }
    }

    fun mayAttempt(nowMillis: Long): Boolean =
        deadlineMillis?.let { nowMillis < it && attempts < policy.maxAttempts } == true

    fun recordAttempt(nowMillis: Long, networkAvailable: Boolean = true): Boolean {
        if (!networkAvailable || !mayAttempt(nowMillis)) return false
        attempts++
        return true
    }

    fun remainingMillis(nowMillis: Long): Long =
        (deadlineMillis?.minus(nowMillis) ?: 0).coerceAtLeast(0)

    fun clear() {
        deadlineMillis = null
        attempts = 0
    }
}
