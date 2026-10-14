package com.framework.innolive.feature.live

data class BroadcastSettings(
    val title: String,
    val description: String,
    val privacy: String,
    val madeForKids: Boolean?,
    val categoryId: String,
)

enum class BroadcastProvider(val wireValue: String) {
    YOUTUBE("youtube"), CHZZK("chzzk");

    companion object {
        fun fromWire(value: String): BroadcastProvider = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown broadcast provider")
    }
}

data class ChzzkBroadcastSettings(
    val title: String = "",
    val categoryType: String = "",
    val categoryId: String = "",
    val tags: List<String> = emptyList(),
)

internal fun ChzzkBroadcastSettings.validationField(): String? {
    if (title.codePointCount(0, title.length) > 100) return "title"
    if (categoryType.isNotEmpty() && categoryType !in setOf("GAME", "SPORTS", "ETC")) return "category_type"
    if (categoryType.isEmpty() && categoryId.isNotEmpty()) return "category_type"
    if (categoryType.isNotEmpty() && categoryId.isEmpty()) return "category_id"
    if (tags.size > 5) return "tags"
    tags.forEachIndexed { index, tag ->
        if (tag.isEmpty() || tag.codePointCount(0, tag.length) > 15 ||
            tag.codePoints().anyMatch { !Character.isLetterOrDigit(it) }) return "tags[$index]"
    }
    // 모든 필드가 비면 서버 기본값으로 준비하는 요청이다. 그 외에는 공백뿐인 제목을 보내지 않는다(broadcast-settings-v1.md).
    val usesServerDefaults = title.isBlank() && categoryType.isBlank() && categoryId.isBlank() && tags.isEmpty()
    if (!usesServerDefaults && title.isBlank()) return "title"
    return null
}

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
