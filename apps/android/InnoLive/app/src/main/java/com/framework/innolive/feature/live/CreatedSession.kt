package com.framework.innolive.feature.live

import org.json.JSONObject

internal data class CreatedSession(
    val sessionId: String,
    val ownerToken: String,
    val anonymizationState: AnonymizationState,
)

internal fun parseCreatedSession(payload: String): CreatedSession {
    val response = JSONObject(payload)
    // 이전 서버의 필드 누락이나 잘못된 타입을 Off로 해석하지 않습니다.
    val enabled = response.optJSONObject("media")?.opt("anonymization_enabled")
    return CreatedSession(
        sessionId = response.optString("session_id").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("세션 ID가 없습니다."),
        ownerToken = response.optString("owner_token").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("세션 owner token이 없습니다."),
        anonymizationState = when (enabled) {
            true -> AnonymizationState.ENABLED
            false -> AnonymizationState.DISABLED
            else -> AnonymizationState.UNKNOWN
        },
    )
}
