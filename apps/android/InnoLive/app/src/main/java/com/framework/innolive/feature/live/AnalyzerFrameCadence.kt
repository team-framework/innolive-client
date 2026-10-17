package com.framework.innolive.feature.live

import kotlin.math.roundToLong

/**
 * 분석기에 도착한 카메라 프레임 간격과 처리 시간을 모읍니다.
 * `STRATEGY_KEEP_ONLY_LATEST`가 버린 프레임은 분석기에 오지 않으므로 카메라 타임스탬프 간격으로 셉니다.
 */
internal class AnalyzerFrameCadence(private val frameIntervalNs: Long = 1_000_000_000L / 30) {
    private var lastTimestampNs: Long? = null
    private var frames = 0L
    private var skipped = 0L
    private var overBudget = 0L
    private var processingTotalNs = 0L
    private var processingMaxNs = 0L
    private var deliveredTotal = 0L

    @Synchronized fun record(timestampNs: Long, processingNs: Long, delivered: Boolean) {
        lastTimestampNs?.let { last ->
            val gap = timestampNs - last
            // 간격이 음수이거나 1초를 넘으면 카메라 재시작으로 보고 건너뛴 프레임에 넣지 않습니다.
            if (gap in 1..1_000_000_000L) {
                skipped += (gap.toDouble() / frameIntervalNs).roundToLong().minus(1).coerceAtLeast(0)
            }
        }
        lastTimestampNs = timestampNs
        frames++
        if (processingNs > frameIntervalNs) overBudget++
        processingTotalNs += processingNs
        if (processingNs > processingMaxNs) processingMaxNs = processingNs
        if (delivered) deliveredTotal++
    }

    /** 구간 값을 돌려주고 비웁니다. 전달 누적 수는 WebRTC `framesEncoded`와 비교하도록 유지합니다. */
    @Synchronized fun drain(): Sample {
        val sample = Sample(frames, skipped, overBudget,
            if (frames > 0) processingTotalNs / frames / 1e6 else null, processingMaxNs / 1e6, deliveredTotal)
        frames = 0
        skipped = 0
        overBudget = 0
        processingTotalNs = 0
        processingMaxNs = 0
        return sample
    }

    data class Sample(
        val frames: Long,
        val cameraSkipped: Long,
        val overBudget: Long,
        val processingAvgMs: Double?,
        val processingMaxMs: Double,
        val deliveredTotal: Long,
    ) {
        override fun toString() = "frames=$frames camera_skipped=$cameraSkipped over_budget=$overBudget " +
            "process_avg_ms=$processingAvgMs process_max_ms=$processingMaxMs delivered_total=$deliveredTotal"
    }
}
