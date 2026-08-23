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
    GOING_LIVE,
    LIVE,
    STOPPING,
    FAILED,
}
