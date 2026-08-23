package com.framework.innolive.feature.youtube

data class StreamingAccount(
    val provider: String,
    val channelId: String,
    val channelTitle: String,
    val reconnectRequired: Boolean,
)
