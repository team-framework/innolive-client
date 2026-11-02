package com.framework.innolive.feature.live.privacy

/**
 * 직렬 AI 스레드에서 동작하는 innolive-ai `StreamTracker`입니다. 낮은 점수 후보는 BoT-SORT 트랙을 이어 갈
 * 때만 보호하고, 이 프레임에서 놓친 트랙은 예측 상자 위치에 마지막 마스크를 한 프레임 동안 유지합니다.
 * 서버와 달리, 높은 점수 검출은 트랙이 확정되기 전부터 보호합니다.
 */
internal class PrivacyObjectTracking {
    private class Seen(val detection: PrivacySegmentation.Detection, val frame: Int)

    private val tracker = PrivacyBotSort()
    private val seen = HashMap<Int, Seen>()
    private var lastTimeSeconds: Double? = null
    private var lastDetectionSeconds: Double? = null
    private var detectionStepSeconds: Double? = null
    private var previous = emptyList<PrivacySegmentation.Detection>()

    /** 아직 추적 중이거나 짧은 검출 공백 뒤 복구할 수 있는 BoT-SORT ID입니다(innolive-ai `live_track_ids`). */
    val liveTrackIds: Set<Int>
        get() = (tracker.trackedTracks + tracker.lostTracks).mapTo(HashSet()) { it.id }

    fun reset() {
        tracker.reset()
        seen.clear()
        lastTimeSeconds = null
        lastDetectionSeconds = null
        detectionStepSeconds = null
        previous = emptyList()
    }

    /**
     * 검출을 건너뛰는 프레임이면 마지막 검출 객체를 BoT-SORT로 [atSeconds]까지 이동한 결과를 반환합니다.
     * 따라갈 대상이 없거나, 확정되지 않은 새 객체·lost 객체가 있거나(장면 변화), 공백이 있어 이 프레임에서
     * 검출해야 하면 null입니다.
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

    /** 보호할 객체를 반환합니다. 입력 순서대로 tracked 또는 높은 점수 검출을 먼저, 그다음 유지된 트랙을 둡니다. */
    fun update(detections: List<PrivacySegmentation.Detection>, atSeconds: Double): List<PrivacySegmentation.Detection> {
        require(atSeconds.isFinite() && detections.size <= PrivacySegmentation.MAX_OBJECTS)
        val previousTime = lastTimeSeconds
        // iOS와 같이 처리 공백이 길면 처음부터 다시 시작합니다. Kalman 단계는 연속으로 처리된 프레임을 전제로 합니다.
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
        /** 건너뛴 프레임은 최대 이만큼 전의 검출만 따라가며, 이보다 느린 프레임은 다시 검출합니다. */
        const val MAX_PREDICTION_SECONDS = .10
        private const val MASK_RETENTION_FRAMES = PrivacyBotSort.TRACK_BUFFER_FRAMES
        private const val HELD_OVERLAP = .5
        private val INPUT = PrivacySegmentation.INPUT_SIZE.toFloat()
    }
}
