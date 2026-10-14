package com.framework.innolive.feature.live.direct

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.SessionNotice
import com.framework.innolive.feature.live.parseBroadcastState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 세션 생성 시 고정하는 실제 송출 해상도입니다. 서버 차감 배수의 기준입니다. */
internal enum class DirectPublishResolution(val wireValue: String) {
    HD_720("720p"),
    FHD("fhd"),
}

internal data class DirectIngest(val provider: BroadcastProvider, val ingestUrl: IngestUrl)

internal data class DirectTargetFailure(val provider: String, val code: String?, val message: String?)

/** YouTube는 [ingestUrl]로 golive 전에 송출을 시작합니다. 치지직은 항상 null입니다. */
internal data class DirectPrepareResult(val state: BroadcastState, val ingestUrl: IngestUrl?)

/**
 * [directIngest]는 이번 응답에서 라이브 상태인 치지직 대상입니다. 재시도 응답에 같은
 * 대상이 다시 올 수 있으므로 이미 연결한 대상은 다시 연결하지 않습니다.
 */
internal data class DirectGoLiveResult(
    val state: BroadcastState?,
    val directIngest: List<DirectIngest>,
    val failedTargets: List<DirectTargetFailure>,
)

internal data class HeartbeatTarget(
    val provider: BroadcastProvider,
    val connected: Boolean,
    val bitrateKbps: Int? = null,
    val reconnects: Int? = null,
)

/** 누적 값은 송출 시작 이후 누적입니다. */
internal data class HeartbeatVideo(
    val fps: Double,
    val droppedFrames: Long,
    val width: Int? = null,
    val height: Int? = null,
)

internal data class HeartbeatAnonymization(
    val failedFrames: Long,
    val enabled: Boolean? = null,
    val inferenceMsP50: Double? = null,
    val inferenceMsP95: Double? = null,
)

internal enum class DeviceThermal(val wireValue: String) {
    NOMINAL("nominal"),
    FAIR("fair"),
    SERIOUS("serious"),
    CRITICAL("critical"),
}

internal data class HeartbeatDevice(
    val thermal: DeviceThermal? = null,
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
    val cpuPct: Int? = null,
)

internal data class DirectHeartbeat(
    val sentAt: Instant,
    val targets: List<HeartbeatTarget>,
    val video: HeartbeatVideo,
    val anonymization: HeartbeatAnonymization,
    val model: String,
    val device: HeartbeatDevice? = null,
) {
    init {
        require(targets.isNotEmpty()) { "하트비트 대상이 없습니다." }
        require(model.isNotBlank()) { "기기 모델이 없습니다." }
    }
}

internal enum class HeartbeatStopReason {
    /** 404: 서버가 세션을 삭제했습니다. */
    SESSION_GONE,

    /** 403: 세션 소유권을 잃었습니다. */
    OWNERSHIP_LOST,

    /** 409 `heartbeat_not_applicable`: 서버에 라이브 대상이 없습니다. */
    NOT_APPLICABLE,
}

internal sealed interface HeartbeatResult {
    /** [stopTargets]의 연결은 즉시 끊습니다. */
    data class Accepted(
        val stopTargets: List<BroadcastProvider>,
        val notices: List<SessionNotice>,
        val broadcastRemainingSeconds: Long?,
    ) : HeartbeatResult

    /** 모든 연결을 끊습니다. */
    data class StopAll(val reason: HeartbeatStopReason) : HeartbeatResult

    /**
     * 400 응답 또는 보낼 수 없는 필수 값(NaN·무한대 fps)입니다. 송출은 유지하고 구현 오류로
     * 다룹니다. 요청 생성 단계에서 막힌 경우 서버로 보내지 않습니다.
     */
    data class Invalid(val field: String?) : HeartbeatResult

    /** 인증 갱신 실패입니다. 송출은 유지하고 다음 주기에 다시 보냅니다. */
    data object Unauthorized : HeartbeatResult

    /** 5xx·네트워크 오류입니다. 송출은 유지하고 다음 주기에 다시 보냅니다. 누락분은 재전송하지 않습니다. */
    data object RetryLater : HeartbeatResult
}

internal fun buildDirectSessionRequest(
    provider: BroadcastProvider,
    resolution: DirectPublishResolution,
): JSONObject = JSONObject()
    .put("provider", provider.wireValue)
    .put("ai_processing", ON_DEVICE_PROCESSING)
    .put("broadcast_resolution", resolution.wireValue)
    .put("metadata", JSONObject().put("client", "innolive-android"))

internal fun buildHeartbeatPayload(heartbeat: DirectHeartbeat): JSONObject {
    val targets = JSONArray()
    heartbeat.targets.forEach { target ->
        targets.put(
            JSONObject()
                .put("provider", target.provider.wireValue)
                .put("connected", target.connected)
                .putIfNotNull("bitrate_kbps", target.bitrateKbps)
                .putIfNotNull("reconnects", target.reconnects),
        )
    }
    // JSON은 NaN·무한대를 표현하지 못합니다. 필수 값은 보내지 않고, 선택 값은 수집 불가로 보고 생략합니다.
    if (!heartbeat.video.fps.isFinite()) throw InvalidHeartbeatFieldException("video.fps")
    val video = JSONObject()
        .put("fps", heartbeat.video.fps)
        .put("dropped_frames", heartbeat.video.droppedFrames)
        .putIfNotNull("width", heartbeat.video.width)
        .putIfNotNull("height", heartbeat.video.height)
    val anonymization = JSONObject()
        .putIfNotNull("enabled", heartbeat.anonymization.enabled)
        .putIfNotNull("inference_ms_p50", heartbeat.anonymization.inferenceMsP50)
        .putIfNotNull("inference_ms_p95", heartbeat.anonymization.inferenceMsP95)
        .put("failed_frames", heartbeat.anonymization.failedFrames)
    val payload = JSONObject()
        .put("sent_at", heartbeat.sentAt.truncatedTo(ChronoUnit.SECONDS).toString())
        .put("targets", targets)
        .put("video", video)
        .put("anonymization", anonymization)
    heartbeat.device?.let { device ->
        val value = JSONObject()
            .putIfNotNull("thermal", device.thermal?.wireValue)
            .putIfNotNull("battery_pct", device.batteryPct)
            .putIfNotNull("charging", device.charging)
            .putIfNotNull("cpu_pct", device.cpuPct)
        if (value.length() > 0) payload.put("device", value)
    }
    return payload
        .put("platform", HEARTBEAT_PLATFORM)
        .put("model", heartbeat.model.trim())
}

/**
 * 응답 본문을 읽지 못했습니다. Android의 org.json 예외 메시지에는 입력 전체가 붙어 스트림 키나
 * owner token이 함께 남으므로, 원문과 cause 없이 이 예외로 바꿉니다.
 */
internal class DirectPublishResponseException : IOException("직접 송출 서버 응답 형식이 올바르지 않습니다.")

/** 응답 원문을 다루는 파싱을 감싸 org.json 예외가 원문을 싣고 밖으로 나가지 않게 합니다. */
internal inline fun <T> parseResponseSafely(parse: () -> T): T = try {
    parse()
} catch (_: JSONException) {
    throw DirectPublishResponseException()
}

internal fun parseDirectPrepare(payload: String, provider: BroadcastProvider): DirectPrepareResult =
    parseResponseSafely { parseDirectPrepareUnsafe(payload, provider) }

private fun parseDirectPrepareUnsafe(payload: String, provider: BroadcastProvider): DirectPrepareResult {
    val json = JSONObject(payload)
    val state = parseBroadcastState(payload, provider)
    check(state == BroadcastState.PREPARED) { "서버가 방송 준비를 확인하지 않았습니다." }
    val ingest = json.opt("ingest_url")?.takeUnless { it == JSONObject.NULL }
    return when (provider) {
        // 서버 모드 세션이나 이전 서버는 주소를 주지 않습니다. 서버 송출로 오해하지 않게 실패로 처리합니다.
        BroadcastProvider.YOUTUBE -> DirectPrepareResult(state, IngestUrl.parse(ingest))
        // 치지직은 연결이 곧 공개 방송이므로 golive 전 주소를 받지 않습니다.
        BroadcastProvider.CHZZK -> {
            check(ingest == null) { "치지직 준비 응답에 송출 주소가 있습니다." }
            DirectPrepareResult(state, null)
        }
    }
}

internal fun parseDirectGoLive(payload: String, provider: BroadcastProvider): DirectGoLiveResult =
    parseResponseSafely { parseDirectGoLiveUnsafe(payload, provider) }

private fun parseDirectGoLiveUnsafe(payload: String, provider: BroadcastProvider): DirectGoLiveResult {
    val json = JSONObject(payload)
    val failures = json.optJSONArray("failed_targets")?.objects().orEmpty().map { item ->
        DirectTargetFailure(item.optString("provider"), item.string("code"), item.string("message"))
    }.toMutableList()
    val ingest = json.optJSONArray("direct_ingest")?.objects().orEmpty().mapNotNull { item ->
        val target = runCatching { BroadcastProvider.fromWire(item.optString("provider")) }.getOrNull()
            ?: return@mapNotNull null
        val url = runCatching { IngestUrl.parse(item.opt("ingest_url")) }.getOrNull()
        if (url == null) {
            // 서버에서는 이미 라이브입니다. 다른 대상 결과는 살리고 이 대상은 stop으로 정리하게 실패로 넘깁니다.
            failures += DirectTargetFailure(target.wireValue, INVALID_INGEST_URL, null)
            return@mapNotNull null
        }
        DirectIngest(target, url)
    }.distinctBy { it.provider }
    return DirectGoLiveResult(parseBroadcastState(payload, provider), ingest, failures)
}

internal fun parseHeartbeatAccepted(payload: String): HeartbeatResult.Accepted =
    parseResponseSafely { parseHeartbeatAcceptedUnsafe(payload) }

private fun parseHeartbeatAcceptedUnsafe(payload: String): HeartbeatResult.Accepted {
    val json = JSONObject(payload)
    val stops = json.optJSONArray("stop_targets")?.let { array ->
        List(array.length()) { index -> array.opt(index) as? String }
    }.orEmpty().mapNotNull { value ->
        value?.let { runCatching { BroadcastProvider.fromWire(it) }.getOrNull() }
    }.distinct()
    val notices = json.optJSONArray("notices")?.objects().orEmpty().mapNotNull { notice ->
        notice.string("code")?.let { code -> SessionNotice(code, notice.string("at")) }
    }
    val remaining = (json.opt("broadcast_remaining_seconds") as? Number)?.toLong()
    return HeartbeatResult.Accepted(stops, notices, remaining)
}

internal class InvalidHeartbeatFieldException(val field: String) : IllegalArgumentException("하트비트 값이 올바르지 않습니다: $field")

internal const val ON_DEVICE_PROCESSING = "on_device"

/** golive 응답의 직접 송출 주소가 올바르지 않을 때 앱이 붙이는 실패 코드입니다. 서버 코드가 아닙니다. */
internal const val INVALID_INGEST_URL = "invalid_ingest_url"
private const val HEARTBEAT_PLATFORM = "android"

private fun JSONObject.putIfNotNull(key: String, value: Any?): JSONObject = when {
    value == null -> this
    value is Double && !value.isFinite() -> this
    else -> put(key, value)
}

private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }

private fun JSONArray.objects(): List<JSONObject> = List(length()) { index -> optJSONObject(index) }.filterNotNull()
