package com.framework.innolive.feature.live.privacy

import java.util.UUID

/** Short-lived identity lease. Geometry never grants an exception by itself. */
internal class PrivacyFaceTracking {
    data class Track(
        val id: String = UUID.randomUUID().toString(),
        var index: Int,
        var box: PrivacySegmentation.Box,
        var lastScheduledSeconds: Double = Double.NEGATIVE_INFINITY,
        var candidate: String? = null,
        var confirmations: Int = 0,
        var lastConfirmedSeconds: Double = Double.NEGATIVE_INFINITY,
        var allowedUntilSeconds: Double = Double.NEGATIVE_INFINITY,
    )

    private var tracks = emptyList<Track>()
    private var lastTimeSeconds: Double? = null

    fun reset() {
        tracks = emptyList()
        lastTimeSeconds = null
    }

    fun update(boxes: Map<Int, PrivacySegmentation.Box>, atSeconds: Double) {
        if (!atSeconds.isFinite()) { reset(); return }
        val previousTime = lastTimeSeconds
        if (previousTime != null && (atSeconds <= previousTime || atSeconds - previousTime >= .20)) {
            tracks = emptyList()
        }
        lastTimeSeconds = atSeconds
        val valid = boxes.filterValues { box ->
            box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                box.width > 0 && box.height > 0
        }
        val isolated = valid.filter { (index, box) ->
            valid.none { (otherIndex, otherBox) -> otherIndex != index && box.intersects(otherBox) > .05f }
        }
        val old = tracks
        tracks = isolated.toSortedMap().map { (index, box) ->
            val candidates = old.filter { it.box.intersects(box) >= .50f }
            val prior = candidates.singleOrNull()
            if (prior != null && isolated.values.count { prior.box.intersects(it) >= .50f } == 1) {
                prior.index = index
                prior.box = box
                prior
            } else Track(index = index, box = box)
        }
    }

    fun next(atSeconds: Double): Track? {
        val next = tracks.filter { atSeconds - it.lastScheduledSeconds >= .25 }
            .minByOrNull { it.lastScheduledSeconds }
        next?.lastScheduledSeconds = atSeconds
        return next?.copy()
    }

    fun accept(trackID: String, match: String?, capturedAtSeconds: Double,
               nowSeconds: Double, sampleAvailable: Boolean = true) {
        val track = tracks.firstOrNull { it.id == trackID } ?: return
        if (!sampleAvailable) return
        if (match == null || nowSeconds < capturedAtSeconds ||
            nowSeconds - capturedAtSeconds >= .75) {
            track.candidate = null
            track.confirmations = 0
            track.allowedUntilSeconds = Double.NEGATIVE_INFINITY
            return
        }
        if (track.candidate == match && capturedAtSeconds > track.lastConfirmedSeconds &&
            capturedAtSeconds - track.lastConfirmedSeconds < 1.0) {
            track.confirmations++
        } else {
            track.candidate = match
            track.confirmations = 1
            track.allowedUntilSeconds = Double.NEGATIVE_INFINITY
        }
        track.lastConfirmedSeconds = capturedAtSeconds
        if (track.confirmations >= 2) track.allowedUntilSeconds = capturedAtSeconds + .75
    }

    fun allowed(atSeconds: Double): Set<Int> {
        val qualified = tracks.filter { it.candidate != null && it.confirmations >= 2 && atSeconds < it.allowedUntilSeconds }
        return qualified.filter { candidate -> qualified.count { it.candidate == candidate.candidate } == 1 }
            .mapTo(mutableSetOf()) { it.index }
    }
}
