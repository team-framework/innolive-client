package com.framework.innolive.feature.youtube

data class StreamingAccount(
    val provider: String,
    val channelId: String,
    val channelTitle: String,
    val reconnectRequired: Boolean,
    /** 서버의 연결 UUID. 다중 채널 이전 서버 응답에는 없다. */
    val accountId: String? = null,
)
