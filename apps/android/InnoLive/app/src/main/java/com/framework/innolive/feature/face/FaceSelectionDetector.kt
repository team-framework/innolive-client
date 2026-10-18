package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 감지 속도를 위해 긴 변을 이 크기로 줄여 ML Kit에 넘깁니다. 좌표는 정규화하므로 원본에 그대로 씁니다. */
private const val DETECTION_MAX_SIDE = 640
private const val SYNC_DETECTION_TIMEOUT_SECONDS = 10L

/** 분석한 원본 프레임 하나입니다. [bitmap]은 바로 선 방향이며 미리보기와 같은 영역을 담고, 소유자가 재활용합니다. */
internal class FaceSelectionFrame(
    val bitmap: Bitmap,
    val faces: List<FaceSelectionCandidate>,
) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/** 홈 화면 원탭 등록이 쓰는 다인 얼굴 감지기입니다. 안정화 없이 매 프레임의 얼굴 상자만 돌려줍니다. */
internal class FaceSelectionDetector : AutoCloseable {
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .build(),
    )
    private val processing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * [bitmap]의 얼굴을 비동기로 찾습니다. 이미 감지 중이면 false를 돌려주고 비트맵을 받지 않습니다.
     * true를 돌려주면 [onResult]가 임의 스레드에서 한 번 호출되며, 프레임 비트맵은 호출자가 넘긴 그대로입니다.
     */
    fun detect(bitmap: Bitmap, onResult: (FaceSelectionFrame?) -> Unit): Boolean {
        if (closed.get() || !processing.compareAndSet(false, true)) return false
        val sample = bitmap.scaledForDetection()
        return try {
            detector.process(InputImage.fromBitmap(sample, 0))
                .addOnSuccessListener { faces ->
                    val scaleX = bitmap.width.toFloat() / sample.width
                    val scaleY = bitmap.height.toFloat() / sample.height
                    val candidates = FaceSelectionGeometry.candidates(
                        boxes = faces.map { face -> face.boundingBox.toFaceBox().scaledBy(scaleX, scaleY) },
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                    )
                    onResult(if (closed.get()) null else FaceSelectionFrame(bitmap, candidates))
                }
                .addOnFailureListener { onResult(null) }
                .addOnCompleteListener {
                    if (sample !== bitmap) sample.recycle()
                    processing.set(false)
                }
            true
        } catch (_: Exception) {
            if (sample !== bitmap) sample.recycle()
            processing.set(false)
            onResult(null)
            true
        }
    }

    /** 등록 이미지 검증용 동기 감지입니다. 백그라운드 스레드에서만 호출하며 실패하면 null입니다. */
    fun detectBoxes(bitmap: Bitmap): List<FaceBox>? {
        if (closed.get()) return null
        return try {
            Tasks.await(
                detector.process(InputImage.fromBitmap(bitmap, 0)),
                SYNC_DETECTION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            ).map { it.boundingBox.toFaceBox() }
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) detector.close()
    }

    private fun Bitmap.scaledForDetection(): Bitmap {
        val longSide = max(width, height)
        if (longSide <= DETECTION_MAX_SIDE) return this
        val scale = DETECTION_MAX_SIDE.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun FaceBox.scaledBy(scaleX: Float, scaleY: Float): FaceBox =
        FaceBox(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY)
}

internal fun Rect.toFaceBox(): FaceBox = FaceBox(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

internal fun FaceBox.toRectF(): RectF = RectF(left, top, right, bottom)

/** 카메라 원본 비트맵을 뷰포트대로 자르고 바로 세웁니다. 원본은 재활용하며 결과는 호출자가 소유합니다. */
internal fun Bitmap.toUprightSelectionFrame(cropRect: Rect, rotationDegrees: Int): Bitmap {
    var current = this
    val bounded = Rect(cropRect)
    if (bounded.intersect(0, 0, current.width, current.height) &&
        bounded.width() > 0 && bounded.height() > 0 &&
        (bounded.width() != current.width || bounded.height() != current.height)
    ) {
        val cropped = Bitmap.createBitmap(current, bounded.left, bounded.top, bounded.width(), bounded.height())
        if (cropped !== current) current.recycle()
        current = cropped
    }
    val rotation = ((rotationDegrees % 360) + 360) % 360
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
    return current
}

/** 선택한 얼굴만 남긴 등록 이미지를 만듭니다. iOS `FaceDetectionService.registrationImage`와 같은 규칙입니다. */
internal object FaceRegistrationImage {
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * 선택한 얼굴 주위를 2배 정사각형으로 잘라 500px로 맞춘 이미지입니다. 다른 얼굴은 검게 가리고
     * 프레임 밖은 서버의 정사각형 여백처럼 검게 채웁니다. 얼굴이 작거나 프레임이 작으면 null입니다.
     */
    fun crop(frame: FaceSelectionFrame, faceIndex: Int): Bitmap? {
        val face = frame.faces.getOrNull(faceIndex) ?: return null
        if (!face.isSelectable) return null
        if (min(frame.width, frame.height) < FaceSelectionGeometry.REGISTRATION_SIZE) return null
        val size = FaceSelectionGeometry.REGISTRATION_SIZE
        val selected = FaceSelectionGeometry.pixelRect(face.rect, frame.width, frame.height)
        val cropRect = FaceSelectionGeometry.registrationCropRect(selected)
        if (cropRect.width <= 0f) return null

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val scale = size / cropRect.width
        canvas.scale(scale, scale)
        canvas.translate(-cropRect.left, -cropRect.top)
        canvas.drawBitmap(frame.bitmap, 0f, 0f, bitmapPaint)
        val others = frame.faces.filterIndexed { index, _ -> index != faceIndex }.map { it.rect }
        maskFaces(canvas, frame.bitmap, others, face.rect)
        return output
    }

    /** [bitmap] 안에서 다시 찾은 다른 얼굴을 가린 새 이미지입니다. 좌표는 [bitmap] 기준 정규화 값입니다. */
    fun maskOthers(bitmap: Bitmap, hiding: List<FaceBox>, keeping: FaceBox): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        maskFaces(Canvas(output), bitmap, hiding, keeping)
        return output
    }

    /** [hiding] 얼굴을 검게 칠하고, 이웃의 가림이 선택한 얼굴을 덮지 않도록 [keeping] 영역을 원본으로 되돌립니다. */
    private fun maskFaces(canvas: Canvas, source: Bitmap, hiding: List<FaceBox>, keeping: FaceBox) {
        if (hiding.isEmpty()) return
        for (other in hiding) {
            canvas.drawRect(
                FaceSelectionGeometry.pixelRect(
                    other,
                    source.width,
                    source.height,
                    FaceSelectionGeometry.MASKED_FACE_SCALE,
                ).toRectF(),
                blackPaint,
            )
        }
        val keep = FaceSelectionGeometry.pixelRect(keeping, source.width, source.height)
        val keepSource = Rect(
            keep.left.roundToInt().coerceIn(0, source.width),
            keep.top.roundToInt().coerceIn(0, source.height),
            keep.right.roundToInt().coerceIn(0, source.width),
            keep.bottom.roundToInt().coerceIn(0, source.height),
        )
        if (keepSource.width() <= 0 || keepSource.height() <= 0) return
        canvas.drawBitmap(source, keepSource, RectF(keepSource), bitmapPaint)
    }

}
