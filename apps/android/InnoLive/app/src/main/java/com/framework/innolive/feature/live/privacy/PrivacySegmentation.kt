package com.framework.innolive.feature.live.privacy

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** iOS 개인정보 보호 파이프라인이 쓰는 일대다 YOLO 세그멘테이션 출력입니다. */
internal object PrivacySegmentation {
    const val INPUT_SIZE = 640
    const val MASK_SIZE = 160
    const val CANDIDATES = 8400
    const val CHANNELS = 32
    private const val DETECTION_CHANNELS = 38
    const val MAX_OBJECTS = 100
    /** BoT-SORT `track_low_thresh`: 더 약한 후보는 기존 트랙을 이어 가는 경우에만 쓸 수 있습니다. */
    const val CONTINUATION_CONFIDENCE = 0.05f
    /** BoT-SORT `track_high_thresh`/`new_track_thresh`이자 항상 보호하는 점수입니다. */
    const val ACTIVATION_CONFIDENCE = 0.25f
    const val NO_TRACK = -1
    /**
     * 클래스별 NMS IoU입니다. innolive-ai와 iOS는 0.70을 쓰지만 Android는 0.45를 유지합니다. 한 얼굴의
     * 중복 상자가 겹치면 등록 얼굴 예외가 취소되기 때문입니다.
     */
    private const val NMS_IOU = 0.45f

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

    /**
     * [trackId]는 [PrivacyObjectTracking]에서 옵니다. [held] 객체는 예측 상자 위치에 유지된 lost 트랙이고,
     * [predicted] 객체는 검출을 건너뛴 프레임에서 BoT-SORT가 이동한 tracked 트랙입니다. 둘 다 새 인스턴스
     * 마스크 대신 트랙의 마지막 마스크를 반복합니다.
     */
    data class Detection(val box: Box, val score: Float, val classId: Int, val coefficients: FloatArray,
                         val trackId: Int = NO_TRACK, val held: Boolean = false, val predicted: Boolean = false) {
        val repeatsMask get() = held || predicted
    }
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

    /** [CONTINUATION_CONFIDENCE]를 넘는 후보입니다. [ACTIVATION_CONFIDENCE] 이상인 후보만 프레임을 실패시킬 수 있습니다. */
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
            val finite = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()
            require(finite || !protected)
            val valid = finite && width > 0 && height > 0
            if (!valid && protected) throw PrivacyFrameContentException("Invalid detection dimensions")
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
                if (candidate.score >= ACTIVATION_CONFIDENCE) throw PrivacyFrameContentException("Too many objects to protect")
                break
            }
            val coefficients = FloatArray(CHANNELS) { channel ->
                values[(6 + channel) * candidateCount + candidate.index].also { require(it.isFinite()) }
            }
            kept += Detection(candidate.box, candidate.score, candidate.classId, coefficients)
        }
        return kept
    }

    /** 검출된 모든 얼굴과 번호판에 대해 불투명한 모델 공간 마스크를 반환합니다. */
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
            // 모델이 빈 인스턴스 마스크를 반환해도 유효한 상자는 보호된 상태로 남아야 합니다.
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
