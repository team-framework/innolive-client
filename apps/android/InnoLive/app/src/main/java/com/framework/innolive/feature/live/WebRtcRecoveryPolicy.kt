package com.framework.innolive.feature.live

import org.json.JSONObject

internal data class WebRtcRecoveryPolicy(
    val windowMillis: Long = 50_000,
    val debounceMillis: Long = 2_000,
    val maxAttempts: Int = 10,
)

internal class RecoveryAccessToken(initialToken: String) {
    var value: String = initialToken.also { require(it.isNotBlank()) }
        private set
    var refreshedForCurrentRecovery: Boolean = false
        private set

    fun updateForRecovery(refreshedToken: String) {
        require(refreshedToken.isNotBlank())
        value = refreshedToken.trim()
        refreshedForCurrentRecovery = true
    }

    fun resetRecovery() {
        refreshedForCurrentRecovery = false
    }
}

internal enum class SignalingSendFailureAction {
    RETRY_NEGOTIATION,
    START_RECOVERY,
    FAIL_CONNECTION,
}

internal fun signalingSendFailureAction(
    recoveryWindowOpen: Boolean,
    hasConnected: Boolean,
): SignalingSendFailureAction = when {
    recoveryWindowOpen -> SignalingSendFailureAction.RETRY_NEGOTIATION
    hasConnected -> SignalingSendFailureAction.START_RECOVERY
    else -> SignalingSendFailureAction.FAIL_CONNECTION
}

internal enum class RecoveryUnauthorizedAction {
    REFRESH_AND_RETRY,
    FAIL_CONNECTION,
}

internal fun recoveryUnauthorizedAction(
    recoveryWindowOpen: Boolean,
    tokenAlreadyRefreshed: Boolean,
): RecoveryUnauthorizedAction =
    if (recoveryWindowOpen && !tokenAlreadyRefreshed) RecoveryUnauthorizedAction.REFRESH_AND_RETRY
    else RecoveryUnauthorizedAction.FAIL_CONNECTION

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
