package com.framework.innolive.feature.live

// UNKNOWN은 서버에서 확인하지 못한 상태이며 비식별화 Off를 의미하지 않습니다.
enum class AnonymizationState {
    UNKNOWN,
    ENABLED,
    DISABLED,
}

// 연결과 비식별화는 독립적으로 갱신하되, 새 연결에서는 이전 세션의 확인값을 버립니다.
internal data class WebRtcSessionState(
    val generation: Long = 0,
    val connection: WebRtcConnectionState = WebRtcConnectionState.IDLE,
    val anonymization: AnonymizationState = AnonymizationState.UNKNOWN,
) {
    fun beginConnection() = WebRtcSessionState(
        generation = generation + 1,
        connection = WebRtcConnectionState.CONNECTING,
    )

    fun endConnection() = WebRtcSessionState(generation = generation + 1)

    fun acceptsCallback(generation: Long): Boolean =
        this.generation == generation &&
            (connection == WebRtcConnectionState.CONNECTING || connection == WebRtcConnectionState.CONNECTED)

    fun connectionChanged(generation: Long, state: WebRtcConnectionState): WebRtcSessionState {
        if (!acceptsCallback(generation)) return this
        return copy(
            connection = state,
            anonymization = when (state) {
                WebRtcConnectionState.IDLE, WebRtcConnectionState.FAILED -> AnonymizationState.UNKNOWN
                else -> anonymization
            },
        )
    }

    fun anonymizationConfirmed(generation: Long, state: AnonymizationState): WebRtcSessionState =
        if (acceptsCallback(generation)) copy(anonymization = state) else this
}
