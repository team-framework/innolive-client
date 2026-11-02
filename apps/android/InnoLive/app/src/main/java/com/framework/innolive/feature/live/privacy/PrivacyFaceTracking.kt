package com.framework.innolive.feature.live.privacy

import java.util.UUID

/**
 * BoT-SORT 트랙 ID를 키로 하는 innolive-ai `StreamRecognition` 트랙 승인(innolive-ai #77)입니다. 아직
 * 통과하지 못한 얼굴은 나타날 때 확인하고, [QUICK_RETRY_FRAMES] 간격으로 [QUICK_RETRY_LIMIT]번 재시도한 뒤
 * [REVALIDATE_FRAMES]마다(AdaFace GPU 경쟁을 절반으로 줄이려고 서버 값 30의 두 배) 확인하며,
 * 얼굴 없이 [MISSING_TRACK_FRAMES] 프레임이 지나면 잊습니다. 일치하는 결과 하나로 트랙을 승인하며,
 * 승인된 트랙은 다시 확인하지 않고 BoT-SORT가 ID를 유지하는 동안(tracked 또는 lost) 예외로 남습니다.
 * 승인은 등록된 얼굴이 삭제되거나, 추적기가 ID를 폐기하거나, 스트림이 초기화될 때만 끝납니다.
 * 서버와 마찬가지로 BoT-SORT가 다른 사람에게 잘못 연결한 ID도 승인을 유지합니다.
 */
internal class PrivacyFaceTracking {
    /** 이 프레임 객체의 [index] 위치에 있는 얼굴입니다. [held] 얼굴은 예측 상자 위치에 유지된 lost 트랙입니다. */
    data class Face(val index: Int, val trackId: Int, val box: PrivacySegmentation.Box, val held: Boolean = false)

    data class Track(
        /** 이 판단의 식별자입니다. 인식 결과가 이 값을 지정하므로 새 판단은 이전 결과를 무시합니다. */
        val id: String = UUID.randomUUID().toString(),
        val trackId: Int,
        var index: Int,
        var box: PrivacySegmentation.Box,
        var held: Boolean = false,
        var lastSeenFrame: Int = 0,
        var lastCheckedFrame: Int = NEVER,
        var quickRetries: Int = 0,
        /** 진행 중인 인식이 있으면 그 캡처 시각과 프레임입니다. */
        var pendingSeconds: Double? = null,
        var pendingFrame: Int = NEVER,
        /** 이 트랙이 AdaFace에서 일치한 등록 얼굴입니다. */
        var approved: String? = null,
    )

    private var tracks = emptyList<Track>()
    private var lastTimeSeconds: Double? = null
    private var frame = 0

    fun reset() {
        tracks = emptyList()
        lastTimeSeconds = null
    }

    /** [liveTrackIds]: 아직 tracked 또는 lost 상태인 BoT-SORT ID입니다. 승인된 트랙은 정확히 그 ID가 살아 있는 동안만 유지됩니다. */
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

    /** 삭제된 등록 얼굴의 승인은 끝나고, 다른 승인은 등록 얼굴이 바뀌어도 유지됩니다. */
    fun retain(registered: Set<String>) {
        for (track in tracks) {
            track.pendingSeconds = null
            if (track.approved != null && track.approved !in registered) track.requireFreshMatch()
        }
    }

    /**
     * 다음에 인식할 검출 트랙입니다. 호출자가 [recognize]할 수 없는 얼굴은 건너뛰어 다른 얼굴을 막지 않게 하며,
     * 호출자는 제출하지 않은 crop을 [cancel]로 알립니다.
     */
    fun next(atSeconds: Double, recognize: (Track) -> Boolean = { true }): Track? {
        val next = tracks.filter {
            it.lastSeenFrame == frame && !it.held && it.pendingSeconds == null && it.due() && recognize(it)
        }.minByOrNull { it.lastCheckedFrame } ?: return null
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

    /** 지연된 인식 결과가 여전히 현재 트랙에 속하는지 검증합니다. */
    fun recognitionMatches(trackID: String, box: PrivacySegmentation.Box): Boolean {
        if (listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() } ||
            box.width <= 0 || box.height <= 0) return false
        return tracks.singleOrNull { it.id == trackID }?.box?.intersects(box)?.let { it >= .5f } == true
    }

    /** 승인된 트랙은 다시 확인하지 않고, 나머지는 빠른 재시도 뒤 재검증 주기를 따릅니다. */
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
