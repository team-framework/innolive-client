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
    STOPPING,
    FAILED,
}

internal val BroadcastState.canPrepare: Boolean
    get() = this == BroadcastState.IDLE || this == BroadcastState.FAILED

internal val BroadcastState.canGoLive: Boolean
    get() = this == BroadcastState.PREPARED

internal val BroadcastState.canStop: Boolean
    get() = this == BroadcastState.PREPARED || this == BroadcastState.LIVE
