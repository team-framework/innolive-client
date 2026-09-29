package com.framework.innolive.feature.live

/** Rejects images captured before the camera reported a settled exposure for the current request. */
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
