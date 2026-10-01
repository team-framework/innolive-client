package com.framework.innolive.feature.settings

import org.json.JSONObject

enum class PlanMode(val value: String) {
    HD_SINGLE("720p_single"), FHD_SINGLE("fhd_single"), HD_MULTI("720p_multi"), FHD_MULTI("fhd_multi");

    companion object {
        fun current(resolution: String?, targetCount: Int): PlanMode =
            if (resolution == "fhd") {
                if (targetCount > 1) FHD_MULTI else FHD_SINGLE
            } else if (targetCount > 1) HD_MULTI else HD_SINGLE
    }
}

data class ModeAvailability(val mode: String, val allowed: Boolean, val seconds: Long?, val multiplier: Long)

data class PlanUsage(
    val plan: String,
    val allowedModes: List<String>,
    val monthlySeconds: Long,
    val maxBroadcastSeconds: Long,
    val usedSeconds: Long,
    val remainingSeconds: Long?,
    val modes: List<ModeAvailability>,
) {
    fun availability(mode: PlanMode): ModeAvailability? = modes.firstOrNull { it.mode == mode.value }
    fun isAllowed(mode: PlanMode): Boolean = mode.value in allowedModes && availability(mode)?.allowed == true

    fun previewRemaining(mode: PlanMode): Long? {
        val monthly = availability(mode)?.seconds
        val cap = maxBroadcastSeconds.takeIf { it > 0 }
        return if (monthly == null) cap else if (cap == null) monthly else minOf(monthly, cap)
    }
}

internal fun parsePlanUsage(planPayload: String, usagePayload: String): PlanUsage {
    val plan = JSONObject(planPayload)
    val usage = JSONObject(usagePayload)
    val name = plan.get("plan") as String
    require(name.isNotBlank() && usage.get("plan") == name)
    val allowed = plan.getJSONArray("allowed_modes")
    val entries = usage.getJSONArray("available_by_mode")
    return PlanUsage(
        plan = name,
        allowedModes = List(allowed.length()) { allowed.get(it) as String },
        monthlySeconds = plan.seconds("monthly_broadcast_seconds")!!,
        maxBroadcastSeconds = plan.seconds("max_per_broadcast_seconds")!!,
        usedSeconds = usage.seconds("used_seconds")!!,
        remainingSeconds = usage.seconds("remaining_seconds", nullable = true),
        modes = List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            ModeAvailability(
                mode = (entry.get("mode") as String).also { require(it.isNotBlank()) },
                allowed = entry.get("allowed") as Boolean,
                seconds = entry.seconds("seconds", nullable = true),
                multiplier = entry.seconds("multiplier")!!.also { require(it > 0) },
            )
        },
    )
}

private fun JSONObject.seconds(key: String, nullable: Boolean = false): Long? {
    val value = get(key)
    if (nullable && value == JSONObject.NULL) return null
    return when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("Invalid $key")
    }.also { require(it >= 0) }
}
