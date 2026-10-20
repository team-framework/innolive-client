// SPDX-License-Identifier: AGPL-3.0-or-later
// Algorithm port referencing Ultralytics 8.4.104 (Ultralytics AGPL-3.0).
package com.framework.innolive.feature.live.privacy

import kotlin.math.max
import kotlin.math.min

/**
 * innolive-ai `config/botsort.yaml` BoT-SORT (ultralytics 8.4.104) in the detector's 640 model space.
 * GMC and ReID stay off like the server's real-time WebRTC path, so association is Kalman-predicted IoU only.
 * Track IDs keep increasing across [reset] so an identity keyed by an old ID can never reach a new object.
 */
internal class PrivacyBotSort {
    enum class State { Tracked, Lost, Removed }

    class Track internal constructor(val id: Int, detection: Candidate, frame: Int) {
        var state = State.Tracked
            internal set
        /** False only for a track born after the first frame until a second detection confirms it. */
        var isActivated = frame == 1
            internal set
        var score = detection.score
            private set
        var classId = detection.classId
            private set
        /** Index of the detection this track was matched to in its latest frame. */
        var detectionIndex = detection.index
            private set
        var frameId = frame
            private set
        val startFrame = frame
        private val kalman = PrivacyKalmanXywh(detection.xywh)

        /** Kalman mean as a box: the detection while tracked, the prediction once lost. */
        val box: PrivacySegmentation.Box get() = kalman.box()

        /** The box moved [fraction] of a detection step along the Kalman velocity, without changing the state. */
        fun predictedBox(fraction: Double): PrivacySegmentation.Box = kalman.box(fraction)

        internal fun predict() = kalman.predict(stopSize = state != State.Tracked)

        internal fun update(detection: Candidate, frame: Int) {
            kalman.update(detection.xywh)
            state = State.Tracked
            isActivated = true
            frameId = frame
            score = detection.score
            classId = detection.classId
            detectionIndex = detection.index
        }
    }

    class Candidate internal constructor(val index: Int, val box: PrivacySegmentation.Box,
                                         val score: Float, val classId: Int) {
        internal val xywh = doubleArrayOf((box.left + box.right) / 2.0, (box.top + box.bottom) / 2.0,
            box.width.toDouble(), box.height.toDouble())
    }

    private var tracked = listOf<Track>()
    private var lost = listOf<Track>()
    private var nextId = 0
    var frameId = 0
        private set
    val trackedTracks: List<Track> get() = tracked
    val lostTracks: List<Track> get() = lost

    fun reset() {
        tracked = emptyList()
        lost = emptyList()
        frameId = 0
    }

    /** Returns the confirmed track ID for each detection, or [PrivacySegmentation.NO_TRACK]. */
    fun update(detections: List<PrivacySegmentation.Detection>): IntArray {
        frameId++
        val activated = ArrayList<Track>()
        val refound = ArrayList<Track>()
        val lostNow = ArrayList<Track>()
        val removedNow = ArrayList<Track>()
        val candidates = detections.mapIndexed { index, detection ->
            Candidate(index, detection.box, detection.score, detection.classId)
        }
        val high = candidates.filter { it.score >= HIGH_THRESHOLD }
        val low = candidates.filter { it.score > LOW_THRESHOLD && it.score < HIGH_THRESHOLD }
        val (confirmed, unconfirmed) = tracked.partition { it.isActivated }
        val pool = joint(confirmed, lost)
        pool.forEach(Track::predict)

        fun match(track: Track, detection: Candidate) {
            if (track.state == State.Tracked) activated += track else refound += track
            track.update(detection, frameId)
        }

        val first = linearAssignment(distances(pool, high, fuseScore = true), high.size, MATCH_THRESHOLD)
        first.matches.forEach { (track, detection) -> match(pool[track], high[detection]) }

        val remaining = first.unmatchedRows.map { pool[it] }.filter { it.state == State.Tracked }
        val second = linearAssignment(distances(remaining, low, fuseScore = false), low.size, SECOND_MATCH_THRESHOLD)
        second.matches.forEach { (track, detection) -> match(remaining[track], low[detection]) }
        for (index in second.unmatchedRows) remaining[index].let { track ->
            if (track.state != State.Lost) { track.state = State.Lost; lostNow += track }
        }

        val leftover = first.unmatchedCols.map { high[it] }
        val third = linearAssignment(distances(unconfirmed, leftover, fuseScore = true), leftover.size,
            UNCONFIRMED_MATCH_THRESHOLD)
        third.matches.forEach { (track, detection) ->
            unconfirmed[track].update(leftover[detection], frameId)
            activated += unconfirmed[track]
        }
        for (index in third.unmatchedRows) unconfirmed[index].let { it.state = State.Removed; removedNow += it }
        for (index in third.unmatchedCols) leftover[index].takeIf { it.score >= NEW_TRACK_THRESHOLD }?.let {
            activated += Track(++nextId, it, frameId)
        }

        for (track in lost) if (frameId - track.frameId > TRACK_BUFFER_FRAMES) {
            track.state = State.Removed
            removedNow += track
        }
        val nextTracked = joint(joint(tracked.filter { it.state == State.Tracked }, activated), refound)
        val nextLost = subtract(subtract(lost, nextTracked) + lostNow, removedNow)
        val (resultTracked, resultLost) = removeDuplicates(nextTracked, nextLost)
        tracked = resultTracked
        lost = resultLost

        val ids = IntArray(detections.size) { PrivacySegmentation.NO_TRACK }
        for (track in tracked) if (track.isActivated) ids[track.detectionIndex] = track.id
        return ids
    }

    private fun distances(tracks: List<Track>, detections: List<Candidate>, fuseScore: Boolean): Array<DoubleArray> =
        Array(tracks.size) { row ->
            val box = tracks[row].box
            DoubleArray(detections.size) { col ->
                val similarity = iou(box, detections[col].box)
                1 - if (fuseScore) similarity * detections[col].score else similarity
            }
        }

    private fun removeDuplicates(a: List<Track>, b: List<Track>): Pair<List<Track>, List<Track>> {
        val dropA = HashSet<Int>()
        val dropB = HashSet<Int>()
        for (p in a.indices) for (q in b.indices) {
            if (1 - iou(a[p].box, b[q].box) >= DUPLICATE_DISTANCE) continue
            if (a[p].frameId - a[p].startFrame > b[q].frameId - b[q].startFrame) dropB += q else dropA += p
        }
        return a.filterIndexed { index, _ -> index !in dropA } to b.filterIndexed { index, _ -> index !in dropB }
    }

    private fun joint(a: List<Track>, b: List<Track>): List<Track> {
        val ids = a.mapTo(HashSet()) { it.id }
        return a + b.filter { it.id !in ids }
    }

    private fun subtract(a: List<Track>, b: List<Track>): List<Track> {
        val ids = b.mapTo(HashSet()) { it.id }
        return a.filter { it.id !in ids }
    }

    companion object {
        const val HIGH_THRESHOLD = PrivacySegmentation.ACTIVATION_CONFIDENCE
        const val LOW_THRESHOLD = PrivacySegmentation.CONTINUATION_CONFIDENCE
        const val NEW_TRACK_THRESHOLD = PrivacySegmentation.ACTIVATION_CONFIDENCE
        const val TRACK_BUFFER_FRAMES = 30
        const val MATCH_THRESHOLD = .80
        private const val SECOND_MATCH_THRESHOLD = .50
        private const val UNCONFIRMED_MATCH_THRESHOLD = .70
        private const val DUPLICATE_DISTANCE = .15

        /** ultralytics `bbox_ioa(iou=True)` in double with its 1e-7 epsilon, kept apart from [PrivacySegmentation.Box.intersects]
         * so association decisions match the server exactly. */
        fun iou(a: PrivacySegmentation.Box, b: PrivacySegmentation.Box): Double {
            val width = max(0.0, min(a.right, b.right).toDouble() - max(a.left, b.left))
            val height = max(0.0, min(a.bottom, b.bottom).toDouble() - max(a.top, b.top))
            val intersection = width * height
            val union = a.width.toDouble() * a.height + b.width.toDouble() * b.height - intersection
            return intersection / (union + 1e-7)
        }
    }
}

internal class PrivacyAssignment(val matches: List<Pair<Int, Int>>, val unmatchedRows: List<Int>, val unmatchedCols: List<Int>)

/**
 * `lap.lapjv(extend_cost=True, cost_limit=threshold)`: an optimal assignment in which leaving a row and a column
 * unmatched costs [threshold], so a pair is kept only when it lowers the total below that.
 */
internal fun linearAssignment(cost: Array<DoubleArray>, cols: Int, threshold: Double): PrivacyAssignment {
    val rows = cost.size
    if (rows == 0 || cols == 0) return PrivacyAssignment(emptyList(), List(rows) { it }, List(cols) { it })
    require(cost.all { row -> row.size == cols && row.all(Double::isFinite) })
    val n = rows + cols
    fun at(row: Int, col: Int) = when {
        row < rows && col < cols -> cost[row][col]
        row >= rows && col >= cols -> 0.0
        else -> threshold / 2
    }
    // Hungarian algorithm with potentials, 1-based with column 0 as the virtual start.
    val u = DoubleArray(n + 1)
    val v = DoubleArray(n + 1)
    val owner = IntArray(n + 1)
    val way = IntArray(n + 1)
    for (row in 1..n) {
        owner[0] = row
        var col0 = 0
        val minimum = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val used = BooleanArray(n + 1)
        do {
            used[col0] = true
            val row0 = owner[col0]
            var delta = Double.POSITIVE_INFINITY
            var col1 = 0
            for (col in 1..n) if (!used[col]) {
                val reduced = at(row0 - 1, col - 1) - u[row0] - v[col]
                if (reduced < minimum[col]) { minimum[col] = reduced; way[col] = col0 }
                if (minimum[col] < delta) { delta = minimum[col]; col1 = col }
            }
            for (col in 0..n) {
                if (used[col]) { u[owner[col]] += delta; v[col] -= delta } else minimum[col] -= delta
            }
            col0 = col1
        } while (owner[col0] != 0)
        do { val previous = way[col0]; owner[col0] = owner[previous]; col0 = previous } while (col0 != 0)
    }
    val rowToCol = IntArray(rows) { -1 }
    val colToRow = IntArray(cols) { -1 }
    for (col in 1..n) {
        val row = owner[col] - 1
        if (row < rows && col - 1 < cols) { rowToCol[row] = col - 1; colToRow[col - 1] = row }
    }
    return PrivacyAssignment(
        rowToCol.withIndex().filter { it.value >= 0 }.map { it.index to it.value },
        rowToCol.indices.filter { rowToCol[it] < 0 },
        colToRow.indices.filter { colToRow[it] < 0 },
    )
}

/**
 * ultralytics `KalmanFilterXYWH`. Its motion model only couples each coordinate with its own velocity and every
 * noise term is diagonal, so the 8x8 covariance stays four independent 2x2 blocks; this stores just those blocks.
 */
internal class PrivacyKalmanXywh(measurement: DoubleArray) {
    private val mean = DoubleArray(8)
    private val position = DoubleArray(4)
    private val cross = DoubleArray(4)
    private val velocity = DoubleArray(4)

    init {
        require(measurement.size == 4 && measurement.all(Double::isFinite))
        measurement.copyInto(mean)
        for (axis in 0 until 4) {
            position[axis] = square(2 * POSITION_WEIGHT * scale(axis))
            velocity[axis] = square(10 * VELOCITY_WEIGHT * scale(axis))
        }
    }

    /** [stopSize]: BOTrack clears the size velocity of a track that is not currently tracked. */
    fun predict(stopSize: Boolean) {
        if (stopSize) { mean[6] = 0.0; mean[7] = 0.0 }
        val positionNoise = DoubleArray(4) { square(POSITION_WEIGHT * scale(it)) }
        val velocityNoise = DoubleArray(4) { square(VELOCITY_WEIGHT * scale(it)) }
        for (axis in 0 until 4) {
            mean[axis] += mean[axis + 4]
            position[axis] += 2 * cross[axis] + velocity[axis] + positionNoise[axis]
            cross[axis] += velocity[axis]
            velocity[axis] += velocityNoise[axis]
        }
    }

    fun update(measurement: DoubleArray) {
        require(measurement.size == 4 && measurement.all(Double::isFinite))
        val measurementNoise = DoubleArray(4) { square(POSITION_WEIGHT * scale(it)) }
        for (axis in 0 until 4) {
            val innovation = position[axis] + measurementNoise[axis]
            val positionGain = position[axis] / innovation
            val velocityGain = cross[axis] / innovation
            val residual = measurement[axis] - mean[axis]
            mean[axis] += positionGain * residual
            mean[axis + 4] += velocityGain * residual
            velocity[axis] -= velocityGain * velocityGain * innovation
            cross[axis] -= positionGain * velocityGain * innovation
            position[axis] -= positionGain * positionGain * innovation
        }
    }

    fun box(fraction: Double = 0.0): PrivacySegmentation.Box {
        val x = mean[0] + fraction * mean[4]
        val y = mean[1] + fraction * mean[5]
        val width = mean[2] + fraction * mean[6]
        val height = mean[3] + fraction * mean[7]
        return PrivacySegmentation.Box((x - width / 2).toFloat(), (y - height / 2).toFloat(),
            (x + width / 2).toFloat(), (y + height / 2).toFloat())
    }

    internal fun mean() = mean.copyOf()

    private fun scale(axis: Int) = if (axis % 2 == 0) mean[2] else mean[3]
    private fun square(value: Double) = value * value

    private companion object {
        const val POSITION_WEIGHT = 1.0 / 20
        const val VELOCITY_WEIGHT = 1.0 / 160
    }
}
