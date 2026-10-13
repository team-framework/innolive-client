package com.framework.innolive.feature.live.status

import androidx.annotation.StringRes
import com.framework.innolive.R
import com.framework.innolive.feature.live.SessionTarget
import kotlin.math.min
import kotlin.math.roundToInt

enum class BroadcastTargetTone {
    LIVE,
    READY,
    PROGRESS,
    ATTENTION,
    ENDED,
}

/** 상태 패널은 플랫폼 이름을 따로 보여 주므로 이름 없는 짧은 상태만 쓴다. */
@get:StringRes
val SessionTarget.statusLabelRes: Int
    get() {
        if (status == "stopped") return R.string.live_status_target_stopped
        when (broadcastPhase) {
            "preparing" -> return R.string.live_status_target_preparing
            "prepared" -> return R.string.live_status_target_prepared
            "going_live" -> return R.string.live_status_target_going_live
        }
        return when (status) {
            "streaming" -> R.string.live_status_target_streaming
            "reconfiguring" -> R.string.live_status_target_reconfiguring
            "reconnecting" -> R.string.live_status_target_reconnecting
            "paused", "paused_reconfiguring", "paused_reconnecting" -> R.string.live_status_target_paused
            "idle" -> R.string.live_status_target_preparing
            else -> R.string.live_status_target_checking
        }
    }

val SessionTarget.tone: BroadcastTargetTone
    get() {
        if (status == "stopped") return BroadcastTargetTone.ENDED
        when (broadcastPhase) {
            "prepared" -> return BroadcastTargetTone.READY
            "preparing", "going_live" -> return BroadcastTargetTone.PROGRESS
        }
        return when (status) {
            "streaming" -> BroadcastTargetTone.LIVE
            "reconnecting", "paused", "paused_reconfiguring", "paused_reconnecting" -> BroadcastTargetTone.ATTENTION
            else -> BroadcastTargetTone.PROGRESS
        }
    }

/** WebRTC outbound-rtp 영상 통계에서 상태 패널이 쓰는 값만 남긴다. */
data class UplinkVideoStats(
    val reason: String,
    val width: Int?,
    val height: Int?,
    val framesPerSecond: Double?,
) {
    companion object {
        fun from(members: Map<String, Any?>): UplinkVideoStats = UplinkVideoStats(
            reason = members["qualityLimitationReason"] as? String ?: "none",
            width = (members["frameWidth"] as? Number)?.toInt(),
            height = (members["frameHeight"] as? Number)?.toInt(),
            framesPerSecond = (members["framesPerSecond"] as? Number)?.toDouble(),
        )
    }
}

enum class BroadcastUplinkLimitation {
    NETWORK,
    DEVICE,
}

data class BroadcastUplinkQuality(
    val shortEdge: Int? = null,
    val framesPerSecond: Int? = null,
    val limitation: BroadcastUplinkLimitation? = null,
) {
    /** 앱이 서버로 실제 올리고 있는 영상의 해상도와 프레임 수 */
    val summary: String?
        get() {
            val edge = shortEdge ?: return null
            val fps = framesPerSecond ?: return "${edge}p"
            return "${edge}p · ${fps}fps"
        }

    companion object {
        val Measuring = BroadcastUplinkQuality()
    }
}

/**
 * WebRTC는 연결 직후 대역폭을 추정하는 몇 초 동안에도 제한 사유를 보고한다.
 * 매 방송 시작마다 경고가 깜빡이지 않도록 연속으로 제한될 때만 경고를 띄운다.
 */
class BroadcastUplinkQualityTracker {
    var quality = BroadcastUplinkQuality.Measuring
        private set
    private var limitedStreak = 0
    private var clearStreak = 0

    fun record(sample: UplinkVideoStats): BroadcastUplinkQuality {
        var limitation = quality.limitation
        val sampleLimitation = limitation(sample.reason)
        if (sampleLimitation != null) {
            limitedStreak++
            clearStreak = 0
            if (limitation != null || limitedStreak >= SAMPLES_TO_WARN) limitation = sampleLimitation
        } else {
            limitedStreak = 0
            clearStreak++
            if (clearStreak >= SAMPLES_TO_CLEAR) limitation = null
        }
        val width = sample.width
        val height = sample.height
        quality = BroadcastUplinkQuality(
            shortEdge = if (width != null && height != null) min(width, height) else quality.shortEdge,
            framesPerSecond = sample.framesPerSecond?.roundToInt(),
            limitation = limitation,
        )
        return quality
    }

    fun reset() {
        quality = BroadcastUplinkQuality.Measuring
        limitedStreak = 0
        clearStreak = 0
    }

    companion object {
        const val SAMPLES_TO_WARN = 3
        const val SAMPLES_TO_CLEAR = 2

        private fun limitation(reason: String): BroadcastUplinkLimitation? = when (reason) {
            "bandwidth" -> BroadcastUplinkLimitation.NETWORK
            "cpu" -> BroadcastUplinkLimitation.DEVICE
            else -> null
        }
    }
}
