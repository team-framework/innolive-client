package com.framework.innolive.feature.live.privacy

import java.util.UUID

/** Two recognition confirmations grant a bounded cache while unambiguous geometry continues. */
internal class PrivacyFaceTracking {
    data class Track(
        val id: String = UUID.randomUUID().toString(),
        var index: Int,
        var box: PrivacySegmentation.Box,
        var lastScheduledSeconds: Double = Double.NEGATIVE_INFINITY,
        var candidate: String? = null,
        var confirmations: Int = 0,
        var lastResultSeconds: Double = Double.NEGATIVE_INFINITY,
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
        if (previousTime != null && (atSeconds <= previousTime || atSeconds - previousTime >= CACHE_SECONDS)) {
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
        if (!atSeconds.isFinite()) return null
        val next = tracks.filter { atSeconds - it.lastScheduledSeconds >= RECHECK_SECONDS }
            .minByOrNull { it.lastScheduledSeconds }
        next?.lastScheduledSeconds = atSeconds
        return next?.copy()
    }

    fun accept(trackID: String, match: String?, capturedAtSeconds: Double,
               nowSeconds: Double, sampleAvailable: Boolean = true) {
        val track = tracks.firstOrNull { it.id == trackID } ?: return
        if (!capturedAtSeconds.isFinite() || !nowSeconds.isFinite()) { reset(); return }
        if (capturedAtSeconds <= track.lastResultSeconds) return
        track.lastResultSeconds = capturedAtSeconds
        if (!sampleAvailable) return
        if (match == null || nowSeconds < capturedAtSeconds ||
            nowSeconds - capturedAtSeconds >= CACHE_SECONDS) {
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
        if (track.confirmations >= 2) track.allowedUntilSeconds = capturedAtSeconds + CACHE_SECONDS
    }

    fun allowed(atSeconds: Double): Set<Int> {
        if (!atSeconds.isFinite()) return emptySet()
        val qualified = tracks.filter { it.candidate != null && it.confirmations >= 2 &&
            atSeconds >= it.lastConfirmedSeconds && atSeconds < it.allowedUntilSeconds }
        return qualified.filter { candidate -> qualified.count { it.candidate == candidate.candidate } == 1 }
            .map { it.index }.toSet()
    }

    /** A crop may contain a different face than the track it was scheduled for. */
    fun recognitionMatches(trackID: String, box: PrivacySegmentation.Box): Boolean {
        if (listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() } ||
            box.width <= 0 || box.height <= 0) return false
        return tracks.singleOrNull { it.id == trackID }?.box?.intersects(box)?.let { it >= .5f } == true
    }

    companion object {
        const val CACHE_SECONDS = .5
        const val RECHECK_SECONDS = .25
    }
}
