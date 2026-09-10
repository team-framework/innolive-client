package com.framework.innolive.feature.live

import org.json.JSONObject

// 확인된 설정값과 별도로 요청의 진행·실패 상태를 보관합니다.
enum class AnonymizationChangeStatus { IDLE, CHANGING, FAILED }

data class AnonymizationChange(
    val status: AnonymizationChangeStatus = AnonymizationChangeStatus.IDLE,
    val requestId: Long = 0,
    val requestedEnabled: Boolean? = null,
    val errorMessage: String? = null,
)

internal fun anonymizationPayload(enabled: Boolean): JSONObject = JSONObject().put("enabled", enabled)

internal fun parseAnonymizationResponse(payload: String, sessionId: String): AnonymizationState {
    val response = JSONObject(payload)
    require(response.optString("session_id") == sessionId) { "응답의 세션이 일치하지 않습니다." }
    return when (response.optJSONObject("media")?.opt("anonymization_enabled")) {
        true -> AnonymizationState.ENABLED
        false -> AnonymizationState.DISABLED
        else -> throw IllegalArgumentException("비식별화 상태를 확인할 수 없습니다.")
    }
}
