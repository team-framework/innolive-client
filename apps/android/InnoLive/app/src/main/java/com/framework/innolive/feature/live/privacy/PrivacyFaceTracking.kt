package com.framework.innolive.feature.live.privacy

import java.util.UUID

/**
 * innolive-ai `StreamRecognition` track decision cache keyed by BoT-SORT track IDs. A track is checked when it
 * appears, retried [QUICK_RETRY_LIMIT] times [QUICK_RETRY_FRAMES] apart while it is not exempt, then revalidated
 * every [REVALIDATE_FRAMES]. An exempt track stays exempt while its revalidation is in flight, and its decision
 * survives up to [MISSING_TRACK_FRAMES] frames without a face. Like iOS, the first exemption needs two matching
 * results, and overlap, a negative or lost result, or a held face due for a check revoke it at once.
 */
internal class PrivacyFaceTracking {
    /** A face at [index] of this frame's objects; a [held] face is a lost track kept at its predicted box. */
    data class Face(val index: Int, val trackId: Int, val box: PrivacySegmentation.Box, val held: Boolean = false)

    data class Track(
        /** Identity of this decision; a recognition result names it, so a new decision ignores old results. */
        val id: String = UUID.randomUUID().toString(),
        val trackId: Int,
        var index: Int,
        var box: PrivacySegmentation.Box,
        var held: Boolean = false,
        var isolated: Boolean = true,
        var lastSeenFrame: Int = 0,
        var lastCheckedFrame: Int = NEVER,
        var quickRetries: Int = 0,
        /** Capture time and frame of the recognition in flight, if any. */
        var pendingSeconds: Double? = null,
        var pendingFrame: Int = NEVER,
        var candidate: String? = null,
        var confirmations: Int = 0,
    ) {
        val exempt get() = candidate != null && confirmations >= CONFIRMATIONS
    }

    private var tracks = emptyList<Track>()
    private var lastTimeSeconds: Double? = null
    private var frame = 0

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
        frame++
        val valid = faces.filter { face ->
            val box = face.box
            box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                box.width > 0 && box.height > 0
        }
        val previous = tracks.associateBy { it.trackId }
        val present = valid.filter { it.trackId != PrivacySegmentation.NO_TRACK }.sortedBy { it.index }.map { face ->
            (previous[face.trackId] ?: Track(trackId = face.trackId, index = face.index, box = face.box)).apply {
                index = face.index
                box = face.box
                held = face.held
                lastSeenFrame = frame
                // Overlapping faces can swap track IDs, so an overlap always needs a fresh match.
                isolated = valid.none { other -> other.index != face.index && face.box.intersects(other.box) > .05f }
                if (!isolated || (held && due())) requireFreshMatch()
            }
        }
        val presentIds = present.mapTo(HashSet()) { it.trackId }
        val missing = tracks.filter { it.trackId !in presentIds && frame - it.lastSeenFrame <= MISSING_TRACK_FRAMES }
        tracks = present + missing
        for (track in tracks) track.pendingSeconds?.let { pending ->
            if (atSeconds - pending >= PENDING_TIMEOUT_SECONDS) { track.pendingSeconds = null; track.requireFreshMatch() }
        }
    }

    /** The detected track to recognize next; the caller reports a crop it did not submit with [cancel]. */
    fun next(atSeconds: Double): Track? {
        val next = tracks.filter {
            it.lastSeenFrame == frame && it.isolated && !it.held && it.pendingSeconds == null && it.due()
        }.minByOrNull { it.lastCheckedFrame } ?: return null
        if (next.lastCheckedFrame != NEVER && !next.exempt) next.quickRetries++
        next.pendingSeconds = atSeconds
        next.pendingFrame = frame
        return next.copy()
    }

    fun cancel(trackID: String) {
        tracks.firstOrNull { it.id == trackID && it.pendingSeconds != null }?.let { track ->
            track.pendingSeconds = null
            if (track.lastCheckedFrame != NEVER && !track.exempt) track.quickRetries--
        }
    }

    fun accept(trackID: String, match: String?, capturedAtSeconds: Double,
               nowSeconds: Double, sampleAvailable: Boolean = true) {
        val track = tracks.firstOrNull { it.id == trackID && it.pendingSeconds == capturedAtSeconds } ?: return
        track.pendingSeconds = null
        track.lastCheckedFrame = track.pendingFrame
        if (nowSeconds < capturedAtSeconds || nowSeconds - capturedAtSeconds >= PENDING_TIMEOUT_SECONDS) {
            track.requireFreshMatch()
            return
        }
        // A missing landmark sample is not evidence of a different person; keep the current decision.
        if (!sampleAvailable) return
        when (match) {
            null -> { track.candidate = null; track.confirmations = 0 }
            track.candidate -> track.confirmations = minOf(CONFIRMATIONS, track.confirmations + 1)
            else -> { track.candidate = match; track.confirmations = 1; track.quickRetries = 0 }
        }
        if (track.exempt) track.quickRetries = 0
    }

    fun allowed(): Set<Int> {
        val exempt = tracks.filter { it.exempt && it.lastSeenFrame == frame && it.isolated }
        return exempt.filter { track -> exempt.count { it.candidate == track.candidate } == 1 }.map { it.index }.toSet()
    }

    /** Validate that a delayed recognition still belongs to the current track. */
    fun recognitionMatches(trackID: String, box: PrivacySegmentation.Box): Boolean {
        if (listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() } ||
            box.width <= 0 || box.height <= 0) return false
        return tracks.singleOrNull { it.id == trackID }?.box?.intersects(box)?.let { it >= .5f } == true
    }

    private fun Track.due(): Boolean {
        if (lastCheckedFrame == NEVER) return true
        val interval = if (!exempt && quickRetries < QUICK_RETRY_LIMIT) QUICK_RETRY_FRAMES else REVALIDATE_FRAMES
        return frame - lastCheckedFrame >= interval
    }

    private fun Track.requireFreshMatch() {
        candidate = null
        confirmations = 0
        lastCheckedFrame = NEVER
    }

    companion object {
        const val CONFIRMATIONS = 2
        const val REVALIDATE_FRAMES = 30
        const val QUICK_RETRY_FRAMES = 5
        const val QUICK_RETRY_LIMIT = 2
        const val MISSING_TRACK_FRAMES = 3
        const val PENDING_TIMEOUT_SECONDS = 1.0
        const val MAX_FRAME_GAP_SECONDS = PrivacyObjectTracking.MAX_FRAME_GAP_SECONDS
        private const val NEVER = -1
    }
}
