package com.framework.innolive.feature.live.privacy

/**
 * innolive-ai `StreamTracker` on the serial AI thread: a low-score candidate is protected only while it continues
 * a BoT-SORT track, and a track lost in this frame keeps its last mask for one frame at the predicted box.
 * Unlike the server, a high-score detection is protected before its track is confirmed.
 */
internal class PrivacyObjectTracking {
    private class Seen(val detection: PrivacySegmentation.Detection, val frame: Int)

    private val tracker = PrivacyBotSort()
    private val seen = HashMap<Int, Seen>()
    private var lastTimeSeconds: Double? = null

    /** BoT-SORT IDs still tracked or recoverable after a brief detection gap (innolive-ai `live_track_ids`). */
    val liveTrackIds: Set<Int>
        get() = (tracker.trackedTracks + tracker.lostTracks).mapTo(HashSet()) { it.id }

    fun reset() {
        tracker.reset()
        seen.clear()
        lastTimeSeconds = null
    }

    /** Returns the objects to protect: tracked or high-score detections in input order, then held tracks. */
    fun update(detections: List<PrivacySegmentation.Detection>, atSeconds: Double): List<PrivacySegmentation.Detection> {
        require(atSeconds.isFinite() && detections.size <= PrivacySegmentation.MAX_OBJECTS)
        val previousTime = lastTimeSeconds
        // Like iOS, a long processing gap starts over: Kalman steps assume consecutive processed frames.
        if (previousTime != null && (atSeconds <= previousTime || atSeconds - previousTime >= MAX_FRAME_GAP_SECONDS)) {
            reset()
        }
        lastTimeSeconds = atSeconds
        val ids = tracker.update(detections)
        val frame = tracker.frameId
        val objects = ArrayList<PrivacySegmentation.Detection>(detections.size)
        detections.forEachIndexed { index, detection ->
            val id = ids[index]
            if (id != PrivacySegmentation.NO_TRACK) {
                objects += detection.copy(trackId = id)
                seen[id] = Seen(detection, frame)
            } else if (detection.score >= PrivacySegmentation.ACTIVATION_CONFIDENCE) objects += detection
        }
        val current = objects.map { it.box }
        for (lost in tracker.lostTracks) {
            if (objects.size == PrivacySegmentation.MAX_OBJECTS) break
            val state = seen[lost.id] ?: continue
            if (frame - state.frame != MASK_HOLD_FRAMES) continue
            val predicted = lost.box
            val target = PrivacySegmentation.Box(
                predicted.left.coerceIn(0f, INPUT), predicted.top.coerceIn(0f, INPUT),
                predicted.right.coerceIn(0f, INPUT), predicted.bottom.coerceIn(0f, INPUT))
            if (!(target.width > 0 && target.height > 0)) continue
            if (current.any { PrivacyBotSort.iou(target, it) >= HELD_OVERLAP }) continue
            objects += state.detection.copy(box = target, score = state.detection.score * HOLD_CONFIDENCE_DECAY,
                trackId = lost.id, held = true)
        }
        seen.entries.removeAll { frame - it.value.frame > MASK_RETENTION_FRAMES }
        return objects
    }

    companion object {
        const val MASK_HOLD_FRAMES = 1
        const val HOLD_CONFIDENCE_DECAY = .90f
        const val MAX_FRAME_GAP_SECONDS = 1.0
        private const val MASK_RETENTION_FRAMES = PrivacyBotSort.TRACK_BUFFER_FRAMES
        private const val HELD_OVERLAP = .5
        private val INPUT = PrivacySegmentation.INPUT_SIZE.toFloat()
    }
}
