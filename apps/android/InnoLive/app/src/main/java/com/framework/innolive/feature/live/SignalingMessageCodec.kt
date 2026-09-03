package com.framework.innolive.feature.live

import org.json.JSONObject

/**
 * Messages received from the authenticated v2 signaling endpoint.
 *
 * The server includes a session id on every answer and ICE acknowledgement.
 * Keeping it in the model lets the connection owner reject a response that
 * belongs to an older or otherwise unrelated session before touching native
 * WebRTC state.
 */
internal sealed interface ServerMessage {
    data class Answer(
        val sessionId: String,
        val sdp: String,
    ) : ServerMessage

    data class Error(
        val code: String,
        val message: String,
    ) : ServerMessage

    data class IceCandidateAdded(
        val sessionId: String,
        val endOfCandidates: Boolean,
        val queued: Boolean,
        val iceConnectionState: String,
        val connectionState: String,
    ) : ServerMessage
}

internal object SignalingMessageCodec {
    fun decode(payload: String, expectedSessionId: String? = null): ServerMessage {
        val response = JSONObject(payload)
        response.requireAllowedKeys(ROOT_ALLOWED_KEYS)
        return when (val type = response.requiredString("type")) {
            "answer" -> {
                val sessionId = response.requiredNonBlankString(
                    key = "session_id",
                    message = "서버 answer에 세션 ID가 없습니다.",
                )
                ensureExpectedSession(expectedSessionId, sessionId)
                ServerMessage.Answer(
                    sessionId = sessionId,
                    sdp = response.requiredNonBlankString(
                        key = "sdp",
                        message = "서버 answer에 SDP가 없습니다.",
                    ),
                )
            }

            "error" -> {
                val error = response.requiredObject(
                    key = "error",
                    message = "signaling 응답의 error가 객체가 아닙니다.",
                )
                error.requireAllowedKeys(ERROR_ALLOWED_KEYS)
                error.validateDetails()
                ServerMessage.Error(
                    code = error.requiredNonBlankString(
                        key = "code",
                        message = "서버 error에 오류 코드가 없습니다.",
                    ),
                    message = error.requiredNonBlankString(
                        key = "message",
                        message = "서버 error에 오류 메시지가 없습니다.",
                    ),
                )
            }

            "ice_candidate_added" -> {
                val sessionId = response.requiredNonBlankString(
                    key = "session_id",
                    message = "서버 ICE 응답에 세션 ID가 없습니다.",
                )
                ensureExpectedSession(expectedSessionId, sessionId)
                ServerMessage.IceCandidateAdded(
                    sessionId = sessionId,
                    endOfCandidates = response.requiredBoolean("end_of_candidates"),
                    queued = response.requiredBoolean("queued"),
                    iceConnectionState = response.requiredString("ice_connection_state"),
                    connectionState = response.requiredString("connection_state"),
                )
            }

            else -> throw IllegalArgumentException("지원하지 않는 signaling 응답입니다: $type")
        }
    }

    private fun ensureExpectedSession(expectedSessionId: String?, actualSessionId: String) {
        if (expectedSessionId != null && expectedSessionId != actualSessionId) {
            throw SignalingSessionMismatchException(expectedSessionId, actualSessionId)
        }
    }

    private val ROOT_ALLOWED_KEYS = setOf(
        "session_id",
        "type",
        "owner_token",
        "access_token",
        "sdp",
        "candidate",
        "sdpMid",
        "sdpMLineIndex",
        "end_of_candidates",
        "queued",
        "ice_connection_state",
        "connection_state",
        "error",
    )

    private val ERROR_ALLOWED_KEYS = setOf("code", "message", "details")
}

internal fun parseServerMessage(
    payload: String,
    expectedSessionId: String? = null,
): ServerMessage = SignalingMessageCodec.decode(payload, expectedSessionId)

internal class SignalingSessionMismatchException(
    val expectedSessionId: String,
    val actualSessionId: String,
) : IllegalArgumentException(
    "signaling 응답의 세션 ID가 현재 세션과 다릅니다.",
)

private fun JSONObject.requiredNonBlankString(key: String, message: String): String =
    requiredString(key).takeIf { it.isNotBlank() } ?: throw IllegalArgumentException(message)

private fun JSONObject.requiredString(key: String): String {
    if (!has(key) || isNull(key)) {
        throw IllegalArgumentException("signaling 응답에 필수 필드가 없습니다: $key")
    }
    return get(key) as? String
        ?: throw IllegalArgumentException("signaling 응답의 $key가 문자열이 아닙니다.")
}

private fun JSONObject.requiredBoolean(key: String): Boolean {
    if (!has(key) || isNull(key)) {
        throw IllegalArgumentException("signaling 응답에 필수 필드가 없습니다: $key")
    }
    return get(key) as? Boolean
        ?: throw IllegalArgumentException("signaling 응답의 $key가 Boolean이 아닙니다.")
}

private fun JSONObject.requiredObject(key: String, message: String): JSONObject {
    if (!has(key) || isNull(key)) {
        throw IllegalArgumentException("signaling 응답에 필수 필드가 없습니다: $key")
    }
    return get(key) as? JSONObject ?: throw IllegalArgumentException(message)
}

private fun JSONObject.requireAllowedKeys(allowedKeys: Set<String>) {
    keys().asSequence().firstOrNull { key -> key !in allowedKeys }?.let { key ->
        throw IllegalArgumentException("signaling 응답에 지원하지 않는 필드가 있습니다: $key")
    }
}

private fun JSONObject.validateDetails() {
    if (has("details") && (isNull("details") || get("details") !is JSONObject)) {
        throw IllegalArgumentException("signaling 응답의 details가 객체가 아닙니다.")
    }
}
