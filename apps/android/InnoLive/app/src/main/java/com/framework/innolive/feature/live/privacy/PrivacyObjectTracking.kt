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
    private var lastDetectionSeconds: Double? = null
    private var detectionStepSeconds: Double? = null
    private var previous = emptyList<PrivacySegmentation.Detection>()

    fun reset() {
        tracker.reset()
        seen.clear()
        lastTimeSeconds = null
        lastDetectionSeconds = null
        detectionStepSeconds = null
        previous = emptyList()
    }

    /**
     * The last detection's objects moved by BoT-SORT to [atSeconds] for a frame that skips detection, or null when
     * this frame must detect: nothing to follow, a new unconfirmed or lost object (the scene is changing), or a gap.
     */
    fun predict(atSeconds: Double): List<PrivacySegmentation.Detection>? {
        val previousTime = lastTimeSeconds ?: return null
        val detected = lastDetectionSeconds ?: return null
        if (!atSeconds.isFinite() || atSeconds <= previousTime || atSeconds - detected >= MAX_PREDICTION_SECONDS) return null
        if (previous.isEmpty() || previous.any { it.trackId == PrivacySegmentation.NO_TRACK || it.held }) return null
        val step = detectionStepSeconds?.takeIf { it > 0 } ?: (atSeconds - detected)
        val fraction = ((atSeconds - detected) / step).coerceIn(0.0, 1.0)
        val tracks = tracker.trackedTracks.associateBy { it.id }
        val objects = previous.map { detection ->
            val track = tracks[detection.trackId] ?: return null
            val box = clipped(track.predictedBox(fraction)) ?: return null
            detection.copy(box = box, predicted = true)
        }
        lastTimeSeconds = atSeconds
        return objects
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
        lastDetectionSeconds?.let { detectionStepSeconds = atSeconds - it }
        lastDetectionSeconds = atSeconds
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
            val target = clipped(lost.box) ?: continue
            if (current.any { PrivacyBotSort.iou(target, it) >= HELD_OVERLAP }) continue
            objects += state.detection.copy(box = target, score = state.detection.score * HOLD_CONFIDENCE_DECAY,
                trackId = lost.id, held = true)
        }
        seen.entries.removeAll { frame - it.value.frame > MASK_RETENTION_FRAMES }
        previous = objects
        return objects
    }

    private fun clipped(box: PrivacySegmentation.Box): PrivacySegmentation.Box? = PrivacySegmentation.Box(
        box.left.coerceIn(0f, INPUT), box.top.coerceIn(0f, INPUT),
        box.right.coerceIn(0f, INPUT), box.bottom.coerceIn(0f, INPUT)).takeIf { it.width > 0 && it.height > 0 }

    companion object {
        const val MASK_HOLD_FRAMES = 1
        const val HOLD_CONFIDENCE_DECAY = .90f
        const val MAX_FRAME_GAP_SECONDS = 1.0
        /** A skipped frame follows a detection at most this long ago; a slower frame detects again. */
        const val MAX_PREDICTION_SECONDS = .10
        private const val MASK_RETENTION_FRAMES = PrivacyBotSort.TRACK_BUFFER_FRAMES
        private const val HELD_OVERLAP = .5
        private val INPUT = PrivacySegmentation.INPUT_SIZE.toFloat()
    }
}
