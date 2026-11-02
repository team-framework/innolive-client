package com.framework.innolive.feature.live

/** 현재 요청에 대해 카메라가 노출 안정화를 알리기 전에 캡처한 이미지는 거부합니다. */
internal class PreviewExposureFrameGate {
    private var latestTimestamp = Long.MIN_VALUE
    private var firstSettledTimestamp: Long? = null
    private var exposureEV = 0f

    @Synchronized fun begin() {
        latestTimestamp = Long.MIN_VALUE
        firstSettledTimestamp = null
    }

    @Synchronized fun record(timestamp: Long, appliedEV: Float, settled: Boolean) {
        if (timestamp <= latestTimestamp || !appliedEV.isFinite()) return
        latestTimestamp = timestamp
        if (!settled) {
            firstSettledTimestamp = null
            return
        }
        if (firstSettledTimestamp == null || exposureEV != appliedEV) firstSettledTimestamp = timestamp
        exposureEV = appliedEV
    }

    @Synchronized fun exposureForFrame(timestamp: Long): Float? {
        val first = firstSettledTimestamp ?: return null
        return exposureEV.takeIf { timestamp >= first }
    }
}
