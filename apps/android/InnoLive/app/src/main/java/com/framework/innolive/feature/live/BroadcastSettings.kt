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
    PAUSING,
    PAUSED,
    RESUMING,
    CANCELLING_PREPARATION,
    STOPPING,
    FAILED,
}

internal val BroadcastState.canPrepare: Boolean
    get() = this == BroadcastState.IDLE || this == BroadcastState.FAILED

internal val BroadcastState.canGoLive: Boolean
    get() = this == BroadcastState.PREPARED

internal val BroadcastState.canPause: Boolean
    get() = this == BroadcastState.LIVE

internal val BroadcastState.canResume: Boolean
    get() = this == BroadcastState.PAUSED

internal val BroadcastState.canStop: Boolean
    get() = this == BroadcastState.PREPARED || this == BroadcastState.LIVE || this == BroadcastState.PAUSED

internal fun BroadcastState.stoppingState(): BroadcastState = when (this) {
    BroadcastState.PREPARED -> BroadcastState.CANCELLING_PREPARATION
    BroadcastState.LIVE,
    BroadcastState.PAUSED -> BroadcastState.STOPPING
    else -> throw IllegalStateException("방송을 중지할 수 없는 상태입니다: $this")
}
