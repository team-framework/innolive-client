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
    private const val MIN_CONFIDENCE = 0.25f

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

    data class Detection(val box: Box, val score: Float, val classId: Int, val coefficients: FloatArray)

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

    fun detections(values: FloatArray, candidateCount: Int = CANDIDATES): List<Detection> {
        require(candidateCount > 0 && values.size == DETECTION_CHANNELS * candidateCount)
        val found = ArrayList<Detection>()
        for (index in 0 until candidateCount) {
            val face = values[4 * candidateCount + index]
            val plate = values[5 * candidateCount + index]
            require(face.isFinite() && plate.isFinite())
            val score = max(face, plate)
            if (score < MIN_CONFIDENCE) continue
            val x = values[index]
            val y = values[candidateCount + index]
            val width = values[2 * candidateCount + index]
            val height = values[3 * candidateCount + index]
            require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && width > 0 && height > 0)
            val box = Box(
                max(0f, x - width / 2), max(0f, y - height / 2),
                min(INPUT_SIZE.toFloat(), x + width / 2), min(INPUT_SIZE.toFloat(), y + height / 2),
            )
            if (box.width <= 0 || box.height <= 0) continue
            val coefficients = FloatArray(CHANNELS) { channel ->
                values[(6 + channel) * candidateCount + index].also { require(it.isFinite()) }
            }
            found += Detection(box, score, if (face >= plate) 0 else 1, coefficients)
        }
        val kept = ArrayList<Detection>()
        for (candidate in found.sortedByDescending { it.score }) {
            if (kept.none { it.classId == candidate.classId && it.box.intersects(candidate.box) > 0.45f }) {
                kept += candidate
                require(kept.size <= 100) { "Too many objects to protect" }
            }
        }
        return kept
    }

    /** Returns an opaque model-space mask for every detected face and plate. */
    fun unionMask(detections: List<Detection>, prototypes: FloatArray,
                  areFinite: (FloatArray) -> Boolean = { it.all(Float::isFinite) }): ByteArray {
        val pixels = MASK_SIZE * MASK_SIZE
        require(prototypes.size == CHANNELS * pixels && areFinite(prototypes))
        val union = ByteArray(pixels)
        for (detection in detections) {
            require(detection.coefficients.size == CHANNELS && detection.coefficients.all(Float::isFinite))
            val box = detection.box
            val x0 = max(0, floor(box.left / 4f).toInt())
            val x1 = min(MASK_SIZE, ceil(box.right / 4f).toInt())
            val y0 = max(0, floor(box.top / 4f).toInt())
            val y1 = min(MASK_SIZE, ceil(box.bottom / 4f).toInt())
            if (x0 >= x1 || y0 >= y1) continue
            var covered = false
            for (y in y0 until y1) for (x in x0 until x1) {
                val offset = y * MASK_SIZE + x
                var logit = 0f
                for (channel in 0 until CHANNELS) {
                    logit += detection.coefficients[channel] * prototypes[channel * pixels + offset]
                }
                require(logit.isFinite())
                if (logit > 0f) {
                    union[offset] = -1
                    covered = true
                }
            }
            // A valid box must remain protected even if the model returns an empty instance mask.
            if (!covered) for (y in y0 until y1) for (x in x0 until x1) {
                union[y * MASK_SIZE + x] = -1
            }
        }
        return union
    }
}
