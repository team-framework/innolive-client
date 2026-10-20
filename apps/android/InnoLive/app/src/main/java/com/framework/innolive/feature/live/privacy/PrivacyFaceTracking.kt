package com.framework.innolive.feature.live.privacy

import java.util.UUID

/**
 * Mirrors iOS identity caching while geometry remains unambiguous. Each identity follows one confirmed BoT-SORT
 * track; overlap, disappearance and stale results revoke it, and a face without a confirmed track is never checked.
 */
internal class PrivacyFaceTracking {
    /** A detected face at [index] of this frame's objects with its confirmed BoT-SORT [trackId], if any. */
    data class Face(val index: Int, val trackId: Int, val box: PrivacySegmentation.Box)

    data class Track(
        val id: String = UUID.randomUUID().toString(),
        val trackId: Int,
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

    fun update(faces: List<Face>, atSeconds: Double) {
        if (!atSeconds.isFinite()) { reset(); return }
        val previousTime = lastTimeSeconds
        if (previousTime != null && (atSeconds <= previousTime || atSeconds - previousTime >= MAX_FRAME_GAP_SECONDS)) {
            tracks = emptyList()
        }
        lastTimeSeconds = atSeconds
        val valid = faces.filter { face ->
            val box = face.box
            box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                box.width > 0 && box.height > 0
        }
        val isolated = valid.filter { face ->
            valid.none { other -> other.index != face.index && face.box.intersects(other.box) > .05f }
        }
        val old = tracks.associateBy { it.trackId }
        tracks = isolated.filter { it.trackId != PrivacySegmentation.NO_TRACK }.sortedBy { it.index }.map { face ->
            (old[face.trackId] ?: Track(trackId = face.trackId, index = face.index, box = face.box)).apply {
                index = face.index
                box = face.box
            }
        }
    }

    fun next(atSeconds: Double): Track? {
        val next = tracks.filter { atSeconds - it.lastScheduledSeconds >= RECHECK_SECONDS }
            .minByOrNull { it.lastScheduledSeconds }
        next?.lastScheduledSeconds = atSeconds
        return next?.copy()
    }

    fun accept(trackID: String, match: String?, capturedAtSeconds: Double,
               nowSeconds: Double, sampleAvailable: Boolean = true) {
        val track = tracks.firstOrNull { it.id == trackID } ?: return
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
        val qualified = tracks.filter { it.candidate != null && it.confirmations >= 2 && atSeconds < it.allowedUntilSeconds }
        return qualified.filter { candidate -> qualified.count { it.candidate == candidate.candidate } == 1 }
            .map { it.index }.toSet()
    }

    /** Validate that a delayed recognition still belongs to the current track. */
    fun recognitionMatches(trackID: String, box: PrivacySegmentation.Box): Boolean {
        if (listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() } ||
            box.width <= 0 || box.height <= 0) return false
        return tracks.singleOrNull { it.id == trackID }?.box?.intersects(box)?.let { it >= .5f } == true
    }

    companion object {
        const val CACHE_SECONDS = .75
        const val RECHECK_SECONDS = .25
        const val MAX_FRAME_GAP_SECONDS = .20
    }
}
