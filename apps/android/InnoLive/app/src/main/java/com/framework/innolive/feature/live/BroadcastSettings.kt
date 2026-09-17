package com.framework.innolive.feature.live

data class BroadcastSettings(
    val title: String,
    val description: String,
    val privacy: String,
    val madeForKids: Boolean?,
    val categoryId: String,
)

enum class BroadcastState {
    IDLE,
    SAVING_SETTINGS,
    PREPARING,
    PREPARED,
    GOING_LIVE,
    LIVE,
    CANCELLING_PREPARATION,
    STOPPING,
    FAILED,
}

internal val BroadcastState.canPrepare: Boolean
    get() = this == BroadcastState.IDLE || this == BroadcastState.FAILED

internal val BroadcastState.canGoLive: Boolean
    get() = this == BroadcastState.PREPARED

internal val BroadcastState.canStop: Boolean
    get() = this == BroadcastState.PREPARED || this == BroadcastState.LIVE

internal fun BroadcastState.stoppingState(): BroadcastState = when (this) {
    BroadcastState.PREPARED -> BroadcastState.CANCELLING_PREPARATION
    BroadcastState.LIVE -> BroadcastState.STOPPING
    else -> throw IllegalStateException("방송을 중지할 수 없는 상태입니다: $this")
}
