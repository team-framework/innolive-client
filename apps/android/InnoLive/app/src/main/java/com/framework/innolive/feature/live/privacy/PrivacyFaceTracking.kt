package com.framework.innolive.feature.live.privacy

import java.util.UUID

/**
 * innolive-ai `StreamRecognition` track approval (innolive-ai #77) keyed by BoT-SORT track IDs. A face that has not
 * passed is checked when it appears, retried [QUICK_RETRY_LIMIT] times [QUICK_RETRY_FRAMES] apart, then every
 * [REVALIDATE_FRAMES] (twice the server's 30 to halve AdaFace GPU contention), and is forgotten after
 * [MISSING_TRACK_FRAMES] frames without a face. One matching result
 * approves the track: it is never checked again and stays exempt while BoT-SORT keeps its ID (tracked or lost).
 * Approval ends only when its registered face is deleted, the tracker retires the ID, or the stream resets.
 * Like the server, an ID that BoT-SORT mislinks to another person keeps its approval.
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
        var lastSeenFrame: Int = 0,
        var lastCheckedFrame: Int = NEVER,
        var quickRetries: Int = 0,
        /** Capture time and frame of the recognition in flight, if any. */
        var pendingSeconds: Double? = null,
        var pendingFrame: Int = NEVER,
        /** The registered face this track passed AdaFace as. */
        var approved: String? = null,
    )

    private var tracks = emptyList<Track>()
    private var lastTimeSeconds: Double? = null
    private var frame = 0

    fun reset() {
        tracks = emptyList()
        lastTimeSeconds = null
    }

    /** [liveTrackIds]: BoT-SORT IDs still tracked or lost; an approved track lives exactly as long as its ID. */
    fun update(faces: List<Face>, atSeconds: Double, liveTrackIds: Set<Int>? = null) {
        if (!atSeconds.isFinite()) { reset(); return }
        val previousTime = lastTimeSeconds
        if (previousTime != null && (atSeconds <= previousTime || atSeconds - previousTime >= MAX_FRAME_GAP_SECONDS)) {
            tracks = emptyList()
        }
        lastTimeSeconds = atSeconds
        frame++
        val valid = faces.filter { face ->
            val box = face.box
            face.trackId != PrivacySegmentation.NO_TRACK &&
                box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                box.width > 0 && box.height > 0
        }
        val previous = tracks.associateBy { it.trackId }
        val present = valid.sortedBy { it.index }.map { face ->
            (previous[face.trackId] ?: Track(trackId = face.trackId, index = face.index, box = face.box)).apply {
                index = face.index
                box = face.box
                held = face.held
                lastSeenFrame = frame
                if (held && due()) requireFreshMatch()
            }
        }
        val presentIds = present.mapTo(HashSet()) { it.trackId }
        val missing = tracks.filter { track ->
            track.trackId !in presentIds && if (track.approved != null && liveTrackIds != null) {
                track.trackId in liveTrackIds
            } else frame - track.lastSeenFrame <= MISSING_TRACK_FRAMES
        }
        tracks = present + missing
        for (track in tracks) track.pendingSeconds?.let { pending ->
            if (atSeconds - pending >= PENDING_TIMEOUT_SECONDS) { track.pendingSeconds = null; track.requireFreshMatch() }
        }
    }

    /** Approvals of deleted registered faces end; other approvals survive a registered-face change. */
    fun retain(registered: Set<String>) {
        for (track in tracks) {
            track.pendingSeconds = null
            if (track.approved != null && track.approved !in registered) track.requireFreshMatch()
        }
    }

    /** The detected track to recognize next; the caller reports a crop it did not submit with [cancel]. */
    fun next(atSeconds: Double): Track? {
        val next = tracks.filter { it.lastSeenFrame == frame && !it.held && it.pendingSeconds == null && it.due() }
            .minByOrNull { it.lastCheckedFrame } ?: return null
        if (next.lastCheckedFrame != NEVER) next.quickRetries++
        next.pendingSeconds = atSeconds
        next.pendingFrame = frame
        return next.copy()
    }

    fun cancel(trackID: String) {
        tracks.firstOrNull { it.id == trackID && it.pendingSeconds != null }?.let { track ->
            track.pendingSeconds = null
            if (track.lastCheckedFrame != NEVER) track.quickRetries--
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
        if (sampleAvailable && match != null) track.approved = match
    }

    fun allowed(): Set<Int> =
        tracks.filter { it.approved != null && it.lastSeenFrame == frame }.map { it.index }.toSet()

    /** Validate that a delayed recognition still belongs to the current track. */
    fun recognitionMatches(trackID: String, box: PrivacySegmentation.Box): Boolean {
        if (listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() } ||
            box.width <= 0 || box.height <= 0) return false
        return tracks.singleOrNull { it.id == trackID }?.box?.intersects(box)?.let { it >= .5f } == true
    }

    /** An approved track is never checked again; the others follow the quick-retry then revalidation cadence. */
    private fun Track.due(): Boolean {
        if (approved != null) return false
        if (lastCheckedFrame == NEVER) return true
        val interval = if (quickRetries < QUICK_RETRY_LIMIT) QUICK_RETRY_FRAMES else REVALIDATE_FRAMES
        return frame - lastCheckedFrame >= interval
    }

    private fun Track.requireFreshMatch() {
        approved = null
        lastCheckedFrame = NEVER
    }

    companion object {
        const val REVALIDATE_FRAMES = 60
        const val QUICK_RETRY_FRAMES = 5
        const val QUICK_RETRY_LIMIT = 2
        const val MISSING_TRACK_FRAMES = 3
        const val PENDING_TIMEOUT_SECONDS = 1.0
        const val MAX_FRAME_GAP_SECONDS = PrivacyObjectTracking.MAX_FRAME_GAP_SECONDS
        private const val NEVER = -1
    }
}
