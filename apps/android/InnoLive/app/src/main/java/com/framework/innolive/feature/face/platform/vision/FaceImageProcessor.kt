package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private const val REFERENCE_FACE_SIZE = 500
private const val STABLE_FACE_COUNT = 3
private const val STABLE_FACE_WINDOW_MILLIS = 600L
private const val MAX_FRAME_GAP_MILLIS = 600L
private const val CENTER_MIN = 0.25f
private const val CENTER_MAX = 0.75f
private const val MIN_FACE_FRACTION = 0.15f
private const val MAX_FACE_FRACTION = 0.80f
private const val MAX_CENTER_DELTA = 0.08f
private const val MAX_SIZE_DELTA = 0.15f

internal enum class FaceStabilityStatus {
    NO_FACE,
    MULTIPLE_FACES,
    OFF_CENTER,
    TOO_SMALL,
    MOVING,
    WAITING,
    STABLE,
    DETECTOR_ERROR,
}

internal data class FaceObservation(
    val centerX: Float,
    val centerY: Float,
    val widthFraction: Float,
    val heightFraction: Float,
    val trackingId: Int? = null,
) {
    companion object {
        fun from(
            bounds: Rect,
            imageWidth: Int,
            imageHeight: Int,
            trackingId: Int?,
        ): FaceObservation = FaceObservation(
            centerX = bounds.centerX().toFloat() / imageWidth,
            centerY = bounds.centerY().toFloat() / imageHeight,
            widthFraction = bounds.width().toFloat() / imageWidth,
            heightFraction = bounds.height().toFloat() / imageHeight,
            trackingId = trackingId,
        )
    }
}

internal data class FaceDetectionResult(
    val status: FaceStabilityStatus,
)

internal class StableFaceTracker(
    private val stableFaceCount: Int = STABLE_FACE_COUNT,
    private val stableFaceWindowMillis: Long = STABLE_FACE_WINDOW_MILLIS,
    private val maxFrameGapMillis: Long = MAX_FRAME_GAP_MILLIS,
) {
    private var anchor: FaceObservation? = null
    private var firstStableAt: Long? = null
    private var lastFrameAt: Long? = null
    private var consecutiveFrames = 0

    @Synchronized
    fun update(
        faces: List<FaceObservation>,
        timestampMillis: Long,
    ): FaceStabilityStatus {
        if (faces.isEmpty()) {
            reset()
            return FaceStabilityStatus.NO_FACE
        }
        if (faces.size != 1) {
            reset()
            return FaceStabilityStatus.MULTIPLE_FACES
        }

        val face = faces.single()
        if (!isCentered(face)) {
            reset()
            return FaceStabilityStatus.OFF_CENTER
        }
        if (!hasUsableSize(face)) {
            reset()
            return FaceStabilityStatus.TOO_SMALL
        }

        val previousTimestamp = lastFrameAt
        if (previousTimestamp != null && timestampMillis - previousTimestamp > maxFrameGapMillis) {
            reset()
        }

        val previousAnchor = anchor
        if (previousAnchor == null || hasTrackingIdChanged(previousAnchor, face)) {
            begin(face, timestampMillis)
            return FaceStabilityStatus.WAITING
        }
        if (!isStableAgainstAnchor(previousAnchor, face)) {
            begin(face, timestampMillis)
            return FaceStabilityStatus.MOVING
        }

        lastFrameAt = timestampMillis
        consecutiveFrames += 1
        val firstAt = checkNotNull(firstStableAt)
        return if (
            consecutiveFrames >= stableFaceCount &&
            timestampMillis - firstAt >= stableFaceWindowMillis
        ) {
            FaceStabilityStatus.STABLE
        } else {
            FaceStabilityStatus.WAITING
        }
    }

    @Synchronized
    fun reset() {
        anchor = null
        firstStableAt = null
        lastFrameAt = null
        consecutiveFrames = 0
    }

    private fun begin(face: FaceObservation, timestampMillis: Long) {
        anchor = face
        firstStableAt = timestampMillis
        lastFrameAt = timestampMillis
        consecutiveFrames = 1
    }

    private fun isCentered(face: FaceObservation): Boolean =
        face.centerX in CENTER_MIN..CENTER_MAX && face.centerY in CENTER_MIN..CENTER_MAX

    private fun hasUsableSize(face: FaceObservation): Boolean =
        face.widthFraction in MIN_FACE_FRACTION..MAX_FACE_FRACTION &&
            face.heightFraction in MIN_FACE_FRACTION..MAX_FACE_FRACTION

    private fun hasTrackingIdChanged(
        previous: FaceObservation,
        current: FaceObservation,
    ): Boolean = previous.trackingId != null &&
        current.trackingId != null &&
        previous.trackingId != current.trackingId

    private fun isStableAgainstAnchor(
        previous: FaceObservation,
        current: FaceObservation,
    ): Boolean = abs(previous.centerX - current.centerX) <= MAX_CENTER_DELTA &&
        abs(previous.centerY - current.centerY) <= MAX_CENTER_DELTA &&
        relativeDifference(previous.widthFraction, current.widthFraction) <= MAX_SIZE_DELTA &&
        relativeDifference(previous.heightFraction, current.heightFraction) <= MAX_SIZE_DELTA

    private fun relativeDifference(first: Float, second: Float): Float =
        abs(first - second) / first.coerceAtLeast(0.001f)
}

internal fun ImageProxy.toReferenceFaceBitmap(): Bitmap? {
    val source = toBitmap()
    if (minOf(source.width, source.height) < REFERENCE_FACE_SIZE) {
        source.recycle()
        return null
    }

    var current = source
    val rotation = imageInfo.rotationDegrees
    if (rotation != 0) {
        val rotated = Bitmap.createBitmap(
            current,
            0,
            0,
            current.width,
            current.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true,
        )
        if (rotated !== current) current.recycle()
        current = rotated
    }

    val side = minOf(current.width, current.height)
    val left = (current.width - side) / 2
    val top = (current.height - side) / 2
    val cropped = Bitmap.createBitmap(current, left, top, side, side)
    if (cropped !== current) current.recycle()
    current = cropped

    if (current.width == REFERENCE_FACE_SIZE && current.height == REFERENCE_FACE_SIZE) {
        return current
    }

    val resized = Bitmap.createScaledBitmap(
        current,
        REFERENCE_FACE_SIZE,
        REFERENCE_FACE_SIZE,
        true,
    )
    if (resized !== current) current.recycle()
    return resized
}

internal fun Face.toObservation(imageWidth: Int, imageHeight: Int): FaceObservation =
    FaceObservation.from(
        bounds = boundingBox,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        trackingId = trackingId,
    )

internal class FaceDetectionPipeline : AutoCloseable {
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build(),
    )
    private val tracker = StableFaceTracker()
    private val processing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)

    fun submit(
        bitmap: Bitmap,
        onResult: (Bitmap, FaceDetectionResult) -> Unit,
    ): Boolean {
        if (closed.get() || !processing.compareAndSet(false, true)) return false
        val currentGeneration = generation.get()
        return try {
            detector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { faces ->
                    if (isCurrent(currentGeneration)) {
                        val observations = faces.map { face ->
                            face.toObservation(bitmap.width, bitmap.height)
                        }
                        onResult(
                            bitmap,
                            FaceDetectionResult(
                                tracker.update(observations, SystemClock.elapsedRealtime()),
                            ),
                        )
                    }
                }
                .addOnFailureListener {
                    if (isCurrent(currentGeneration)) {
                        tracker.reset()
                        onResult(
                            bitmap,
                            FaceDetectionResult(FaceStabilityStatus.DETECTOR_ERROR),
                        )
                    }
                }
                .addOnCompleteListener { processing.set(false) }
            true
        } catch (_: Exception) {
            processing.set(false)
            if (isCurrent(currentGeneration)) {
                tracker.reset()
                onResult(
                    bitmap,
                    FaceDetectionResult(FaceStabilityStatus.DETECTOR_ERROR),
                )
            }
            true
        }
    }

    fun reset() {
        generation.incrementAndGet()
        tracker.reset()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            generation.incrementAndGet()
            tracker.reset()
            detector.close()
        }
    }

    private fun isCurrent(currentGeneration: Long): Boolean =
        !closed.get() && generation.get() == currentGeneration
}

internal fun Bitmap.toJpegBytes(quality: Int = 90): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        check(compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "얼굴 이미지를 JPEG로 변환하지 못했습니다."
        }
        output.toByteArray()
    }
