package com.framework.innolive.feature.live

import org.json.JSONArray
import org.json.JSONObject

sealed interface BroadcastRemainingTime {
    data object Unknown : BroadcastRemainingTime
    data object UnlimitedOrInactive : BroadcastRemainingTime
    data class Seconds(val value: Long) : BroadcastRemainingTime
}

data class SessionNotice(val code: String, val at: String?)

data class SessionTarget(
    val provider: String,
    val status: String?,
    val broadcastPhase: String?,
    val stopReason: String?,
    val reconnectAttempts: Long?,
)

data class SessionSnapshot(
    val sessionId: String,
    val provider: String = "youtube",
    val targets: List<SessionTarget>? = null,
    val notices: List<SessionNotice>? = null,
    val remainingTime: BroadcastRemainingTime = BroadcastRemainingTime.Unknown,
    val warnings: List<SessionNotice>? = null,
    val broadcastResolution: String? = null,
    val isRemainingTimeStale: Boolean = false,
    /** 이 세션에서 마지막으로 받은 남은 시간 숫자. 송출이 멈춘 뒤 null이 와도 표시를 유지하는 데 쓴다. */
    val lastRemainingSeconds: Long? = null,
) {
    val visibleTargets: List<SessionTarget>
        get() = targets.orEmpty().filter { it.broadcastPhase != "idle" }

    val bannerNotices: List<SessionNotice>
        get() = (notices.orEmpty() + warnings.orEmpty()).distinctBy { it.code }

    // 동시 송출이면 대상들의 상태를 합칩니다. 하나라도 라이브면 방송 중으로 보고 모두 멈췄을 때만 일시 중지입니다.
    internal fun broadcastState(): BroadcastState? {
        val knownTargets = targets ?: return null
        if (knownTargets.isEmpty()) return BroadcastState.IDLE
        // 알 수 없는 단계의 대상은 다른 대상의 확인된 상태를 가리지 않습니다.
        val states = knownTargets.mapNotNull { it.broadcastState() }.ifEmpty { return null }
        return AggregateStateOrder.firstOrNull { it in states } ?: BroadcastState.IDLE
    }
}

private val AggregateStateOrder = listOf(
    BroadcastState.GOING_LIVE,
    BroadcastState.LIVE,
    BroadcastState.PAUSED,
    BroadcastState.PREPARED,
    BroadcastState.PREPARING,
)

private val PreparedOrLaterStates = setOf(
    BroadcastState.PREPARED, BroadcastState.GOING_LIVE, BroadcastState.LIVE, BroadcastState.PAUSED,
)

/** 선택한 플랫폼 중 서버에서 아직 준비되지 않은 플랫폼입니다. 동시 송출 부분 실패 뒤 다시 준비할 대상입니다. */
internal fun SessionSnapshot?.unpreparedProviders(selected: Set<BroadcastProvider>): List<BroadcastProvider> =
    BroadcastProvider.entries.filter { provider ->
        provider in selected && this?.targets.orEmpty()
            .firstOrNull { it.provider == provider.wireValue }?.broadcastState() !in PreparedOrLaterStates
    }

internal fun SessionTarget.broadcastState(): BroadcastState? = when (broadcastPhase) {
    "idle" -> BroadcastState.IDLE
    "preparing" -> BroadcastState.PREPARING
    "prepared" -> BroadcastState.PREPARED
    "going_live" -> BroadcastState.GOING_LIVE
    "live" -> when (status) {
        "streaming", "reconnecting", "reconfiguring" -> BroadcastState.LIVE
        "paused", "paused_reconnecting", "paused_reconfiguring" -> BroadcastState.PAUSED
        "stopped" -> BroadcastState.IDLE
        else -> null
    }
    else -> null
}

// 부분 응답은 포함된 필드만 갱신합니다. targets: []는 이전 stream으로 되돌리지 않습니다.
internal fun parseSessionSnapshot(
    payload: String,
    sessionId: String,
    previous: SessionSnapshot = SessionSnapshot(sessionId),
    clearWarningsWhenMissing: Boolean = false,
    /** pause·resume·stop처럼 한 대상의 stream만 오는 응답의 대상입니다. 없으면 기본 대상입니다. */
    targetProvider: String? = null,
): SessionSnapshot {
    val response = JSONObject(payload)
    require(previous.sessionId == sessionId)
    if (response.has("session_id")) {
        require(response.opt("session_id") == sessionId) { "응답의 세션이 일치하지 않습니다." }
    }
    val provider = if (targetProvider != null) previous.provider
        else (response.opt("provider") as? String)?.takeIf { it.isNotBlank() } ?: previous.provider
    val streamProvider = targetProvider ?: provider
    val oldTargets = previous.targets.orEmpty()
    val targets = when {
        response.has("targets") -> response.optJSONArray("targets")?.objects()?.map { item ->
            val name = (item.opt("provider") as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("송출 대상이 없습니다.")
            parseSessionTarget(item.getJSONObject("stream"), name, oldTargets.firstOrNull { it.provider == name })
        } ?: previous.targets
        response.optJSONObject("stream") != null || response.has("broadcast_phase") -> {
            val stream = response.optJSONObject("stream") ?: response
            val target = parseSessionTarget(stream, streamProvider, oldTargets.firstOrNull { it.provider == streamProvider })
            if (response.has("session_id")) listOf(target)
            else oldTargets.filter { it.provider != streamProvider } + target
        }
        else -> previous.targets
    }
    val notices = when {
        !response.has("notices") -> previous.notices
        response.isNull("notices") -> emptyList()
        else -> response.optJSONArray("notices")?.objects()?.map { notice ->
            val code = (notice.opt("code") as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("알림 코드가 없습니다.")
            SessionNotice(code, notice.opt("at") as? String)
        } ?: previous.notices
    }
    val remaining = when {
        !response.has("broadcast_remaining_seconds") -> previous.remainingTime
        response.isNull("broadcast_remaining_seconds") -> BroadcastRemainingTime.UnlimitedOrInactive
        else -> response.nonNegativeInteger("broadcast_remaining_seconds")
            ?.let(BroadcastRemainingTime::Seconds) ?: previous.remainingTime
    }
    val warnings = when {
        response.has("warnings") -> if (response.isNull("warnings")) emptyList() else {
            response.optJSONArray("warnings")?.objects()?.map { warning ->
                val code = (warning.opt("code") as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("알림 코드가 없습니다.")
                SessionNotice(code, null)
            } ?: previous.warnings
        }
        clearWarningsWhenMissing -> emptyList()
        else -> previous.warnings
    }
    return SessionSnapshot(
        sessionId, provider, targets, notices, remaining, warnings,
        broadcastResolution = response.opt("broadcast_resolution") as? String ?: previous.broadcastResolution,
        isRemainingTimeStale = if (response.has("broadcast_remaining_seconds") &&
            (response.isNull("broadcast_remaining_seconds") || response.nonNegativeInteger("broadcast_remaining_seconds") != null)) {
            false
        } else previous.isRemainingTimeStale,
        // 응답에 숫자가 실렸을 때만 갱신한다. 필드가 없거나 null이면 이 세션의 마지막 숫자를 유지한다.
        lastRemainingSeconds = response.nonNegativeInteger("broadcast_remaining_seconds") ?: previous.lastRemainingSeconds,
    )
}

private fun parseSessionTarget(stream: JSONObject, provider: String, previous: SessionTarget?): SessionTarget =
    SessionTarget(
        provider = provider,
        status = stream.opt("status") as? String ?: previous?.status,
        broadcastPhase = stream.opt("broadcast_phase") as? String ?: previous?.broadcastPhase,
        stopReason = if (stream.has("stop_reason") && stream.isNull("stop_reason")) null
            else stream.opt("stop_reason") as? String ?: previous?.stopReason,
        reconnectAttempts = stream.nonNegativeInteger("reconnect_attempts") ?: previous?.reconnectAttempts,
    )

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.nonNegativeInteger(key: String): Long? = when (val value = opt(key)) {
    is Int -> value.toLong().takeIf { it >= 0 }
    is Long -> value.takeIf { it >= 0 }
    else -> null
}
