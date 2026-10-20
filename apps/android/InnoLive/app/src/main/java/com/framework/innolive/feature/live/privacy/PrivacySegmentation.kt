package com.framework.innolive.feature.live.privacy

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The one-to-many YOLO segmentation output used by the iOS privacy pipeline. */
internal object PrivacySegmentation {
    const val INPUT_SIZE = 640
    const val MASK_SIZE = 160
    const val CANDIDATES = 8400
    const val CHANNELS = 32
    private const val DETECTION_CHANNELS = 38
    const val MAX_OBJECTS = 100
    /** BoT-SORT `track_low_thresh`: a weaker candidate may only continue an existing track. */
    const val CONTINUATION_CONFIDENCE = 0.05f
    /** BoT-SORT `track_high_thresh`/`new_track_thresh`, and the score that is always protected. */
    const val ACTIVATION_CONFIDENCE = 0.25f
    const val NO_TRACK = -1
    /** innolive-ai detector class-aware NMS IoU. */
    private const val NMS_IOU = 0.70f

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        fun intersects(other: Box): Float {
            val width = max(0f, min(right, other.right) - max(left, other.left))
            val height = max(0f, min(bottom, other.bottom) - max(top, other.top))
            val intersection = width * height
            return intersection / max(this.width * this.height + other.width * other.height - intersection, 0.0001f)
        }
    }

    /** [trackId] comes from [PrivacyObjectTracking]; a [held] object is a lost track kept at its predicted box. */
    data class Detection(val box: Box, val score: Float, val classId: Int, val coefficients: FloatArray,
                         val trackId: Int = NO_TRACK, val held: Boolean = false)
    data class InstanceMask(val detection: Detection, val bytes: ByteArray)

    fun protectedDetections(objects: List<Detection>, exemptFaces: Set<Int>): List<Detection> =
        objects.filterIndexed { index, detection -> detection.classId != 0 || index !in exemptFaces }

    data class Letterbox(val sourceWidth: Int, val sourceHeight: Int) {
        init { require(sourceWidth > 0 && sourceHeight > 0) }
        private val scale = INPUT_SIZE.toFloat() / max(sourceWidth, sourceHeight)
        val resizedWidth = (sourceWidth * scale + 0.5f).toInt()
        val resizedHeight = (sourceHeight * scale + 0.5f).toInt()
        val left = floor((INPUT_SIZE - resizedWidth) / 2f).toInt()
        val top = floor((INPUT_SIZE - resizedHeight) / 2f).toInt()
    }

    /** Candidates above [CONTINUATION_CONFIDENCE]; only those at [ACTIVATION_CONFIDENCE] can fail the frame. */
    fun detections(values: FloatArray, candidateCount: Int = CANDIDATES): List<Detection> {
        require(candidateCount > 0 && values.size == DETECTION_CHANNELS * candidateCount)
        class Candidate(val index: Int, val box: Box, val score: Float, val classId: Int)
        val found = ArrayList<Candidate>()
        for (index in 0 until candidateCount) {
            val face = values[4 * candidateCount + index]
            val plate = values[5 * candidateCount + index]
            require(face.isFinite() && plate.isFinite())
            val score = max(face, plate)
            if (score <= CONTINUATION_CONFIDENCE) continue
            val protected = score >= ACTIVATION_CONFIDENCE
            val x = values[index]
            val y = values[candidateCount + index]
            val width = values[2 * candidateCount + index]
            val height = values[3 * candidateCount + index]
            val valid = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && width > 0 && height > 0
            require(valid || !protected)
            if (!valid) continue
            val box = Box(
                max(0f, x - width / 2), max(0f, y - height / 2),
                min(INPUT_SIZE.toFloat(), x + width / 2), min(INPUT_SIZE.toFloat(), y + height / 2),
            )
            if (box.width <= 0 || box.height <= 0) continue
            if (protected) for (channel in 0 until CHANNELS) {
                require(values[(6 + channel) * candidateCount + index].isFinite())
            }
            found += Candidate(index, box, score, if (face >= plate) 0 else 1)
        }
        val kept = ArrayList<Detection>()
        for (candidate in found.sortedByDescending { it.score }) {
            if (kept.any { it.classId == candidate.classId && it.box.intersects(candidate.box) > NMS_IOU }) continue
            if (kept.size == MAX_OBJECTS) {
                require(candidate.score < ACTIVATION_CONFIDENCE) { "Too many objects to protect" }
                break
            }
            val coefficients = FloatArray(CHANNELS) { channel ->
                values[(6 + channel) * candidateCount + candidate.index].also { require(it.isFinite()) }
            }
            kept += Detection(candidate.box, candidate.score, candidate.classId, coefficients)
        }
        return kept
    }

    /** Returns an opaque model-space mask for every detected face and plate. */
    fun instanceMasks(detections: List<Detection>, prototypes: FloatArray,
                      areFinite: (FloatArray) -> Boolean = { it.all(Float::isFinite) }): List<InstanceMask> {
        val pixels = MASK_SIZE * MASK_SIZE
        require(prototypes.size == CHANNELS * pixels && areFinite(prototypes))
        return detections.map { detection ->
            require(detection.coefficients.size == CHANNELS && detection.coefficients.all(Float::isFinite))
            val box = detection.box
            val x0 = max(0, floor(box.left / 4f).toInt())
            val x1 = min(MASK_SIZE, ceil(box.right / 4f).toInt())
            val y0 = max(0, floor(box.top / 4f).toInt())
            val y1 = min(MASK_SIZE, ceil(box.bottom / 4f).toInt())
            val bytes = ByteArray(pixels)
            if (x0 >= x1 || y0 >= y1) return@map InstanceMask(detection, bytes)
            var covered = false
            for (y in y0 until y1) for (x in x0 until x1) {
                val offset = y * MASK_SIZE + x
                var logit = 0f
                for (channel in 0 until CHANNELS) {
                    logit += detection.coefficients[channel] * prototypes[channel * pixels + offset]
                }
                require(logit.isFinite())
                if (logit > 0f) {
                    bytes[offset] = -1
                    covered = true
                }
            }
            // A valid box must remain protected even if the model returns an empty instance mask.
            if (!covered) for (y in y0 until y1) for (x in x0 until x1) {
                bytes[y * MASK_SIZE + x] = -1
            }
            InstanceMask(detection, bytes)
        }
    }

    fun union(masks: List<InstanceMask>): ByteArray {
        val pixels = MASK_SIZE * MASK_SIZE
        val union = ByteArray(pixels)
        for (instance in masks) {
            require(instance.bytes.size == pixels)
            for (i in union.indices) if ((instance.bytes[i].toInt() and 255) > (union[i].toInt() and 255)) {
                union[i] = instance.bytes[i]
            }
        }
        return union
    }

    fun unionMask(detections: List<Detection>, prototypes: FloatArray,
                  areFinite: (FloatArray) -> Boolean = { it.all(Float::isFinite) }): ByteArray =
        union(instanceMasks(detections, prototypes, areFinite))
}
