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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val SYNC_DETECTION_TIMEOUT_SECONDS = 10L

/** 얼굴 선택 프레임의 원본 픽셀입니다. 등록할 때만 전체 해상도 비트맵으로 바꿉니다. */
internal interface FaceSelectionImage {
    /** 바로 서고 전면 카메라면 미리보기처럼 좌우 반전된 전체 해상도 비트맵입니다. 호출자가 재활용합니다. */
    fun toUprightBitmap(): Bitmap

    fun release()
}

/**
 * 분석한 원본 프레임 하나입니다. [width]x[height]와 [faces] 좌표는 바로 서고 미리보기처럼 좌우 반전된
 * 프레임 기준이며 미리보기와 같은 영역을 담습니다. 소유자가 [release]합니다.
 */
internal class FaceSelectionFrame(
    val image: FaceSelectionImage,
    val width: Int,
    val height: Int,
    val faces: List<FaceSelectionCandidate>,
) {
    private val released = AtomicBoolean(false)

    val isReleased: Boolean get() = released.get()

    fun release() {
        if (released.compareAndSet(false, true)) image.release()
    }
}

/**
 * 홈 화면 원탭 등록이 쓰는 다인 얼굴 감지기입니다. 안정화 없이 매 프레임의 얼굴 상자만 돌려줍니다.
 * [contours]면 고를 수 있는 얼굴이 있을 때 윤곽 감지를 한 번 더 돌려 얼굴 모양 윤곽선을 붙입니다.
 * ML Kit 윤곽 모드는 가장 두드러진 얼굴에만 윤곽을 주고 여러 얼굴 감지를 보장하지 않으므로, 얼굴 목록은 항상
 * 상자 감지기 결과를 쓰고 윤곽은 겹치는 상자에만 붙입니다. 나머지 얼굴은 타원으로 그립니다.
 */
internal class FaceSelectionDetector(contours: Boolean = false) : AutoCloseable {
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .build(),
    )
    private val contourDetector: FaceDetector? = if (contours) {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build(),
        )
    } else {
        null
    }
    private val processing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * [bitmap]의 얼굴을 비동기로 찾습니다. 좌표는 정규화하고, 선택 가능 크기는 원본 [sourceWidth]x[sourceHeight]
     * 기준으로 판단합니다. 이미 감지 중이면 false를 돌려주고 비트맵을 받지 않습니다. true를 돌려주면 [onResult]가
     * 한 번 호출되며, 그때까지 [bitmap]을 재활용하면 안 됩니다. 실패하면 null입니다.
     */
    fun detect(
        bitmap: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        onResult: (List<FaceSelectionCandidate>?) -> Unit,
    ): Boolean {
        if (closed.get() || !processing.compareAndSet(false, true)) return false
        val finished = AtomicBoolean(false)
        fun finish(result: List<FaceSelectionCandidate>?) {
            if (!finished.compareAndSet(false, true)) return
            processing.set(false)
            onResult(if (closed.get()) null else result)
        }
        try {
            detector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { faces ->
                    val boxes = faces.map { it.boundingBox.toFaceBox() }
                    val scaleX = sourceWidth.toFloat() / bitmap.width
                    val scaleY = sourceHeight.toFloat() / bitmap.height
                    val candidates = FaceSelectionGeometry.candidates(
                        boxes = boxes.map { box ->
                            FaceBox(box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY)
                        },
                        imageWidth = sourceWidth,
                        imageHeight = sourceHeight,
                    )
                    val contourClient = contourDetector
                    if (contourClient == null || closed.get() || candidates.none { it.isSelectable }) {
                        finish(candidates)
                        return@addOnSuccessListener
                    }
                    try {
                        contourClient.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { contourFaces ->
                                val contours = FaceSelectionGeometry.matchContours(
                                    boxes = boxes,
                                    contourFaces = contourFaces.mapNotNull { it.normalizedContour(bitmap) },
                                )
                                finish(candidates.mapIndexed { index, face -> face.copy(contour = contours[index]) })
                            }
                            .addOnFailureListener { finish(candidates) }
                            .addOnCanceledListener { finish(candidates) }
                    } catch (_: Exception) {
                        finish(candidates)
                    }
                }
                .addOnFailureListener { finish(null) }
                .addOnCanceledListener { finish(null) }
        } catch (_: Exception) {
            finish(null)
        }
        return true
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
        if (closed.compareAndSet(false, true)) {
            detector.close()
            contourDetector?.close()
        }
    }

    /** 윤곽 얼굴의 픽셀 상자와 [bitmap] 기준 정규화 윤곽점입니다. 윤곽이 없으면 null입니다. */
    private fun Face.normalizedContour(bitmap: Bitmap): Pair<FaceBox, List<FacePoint>>? {
        val points = getContour(FaceContour.FACE)?.points?.takeIf { it.isNotEmpty() } ?: return null
        return boundingBox.toFaceBox() to points.map { FacePoint(it.x / bitmap.width, it.y / bitmap.height) }
    }
}

internal fun Rect.toFaceBox(): FaceBox = FaceBox(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

internal fun FaceBox.toRectF(): RectF = RectF(left, top, right, bottom)

private val uprightPaint = Paint(Paint.FILTER_BITMAP_FLAG)

/**
 * 카메라 방향의 비트맵을 [rotationDegrees]만큼 세우고 [mirrored]면 좌우 반전합니다. 좌표 변환은
 * [FaceSelectionGeometry.uprightTransform]을 그대로 씁니다. 원본은 재활용하며 결과는 호출자가 소유합니다.
 */
internal fun Bitmap.toUpright(rotationDegrees: Int, mirrored: Boolean): Bitmap {
    if (((rotationDegrees % 360) + 360) % 360 == 0 && !mirrored) return this
    val (width, height) = FaceSelectionGeometry.uprightSize(this.width, this.height, rotationDegrees)
    val values = FaceSelectionGeometry.uprightTransform(this.width, this.height, rotationDegrees, mirrored)
    val output = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (error: Throwable) {
        recycle()
        throw error
    }
    val matrix = Matrix().apply {
        setValues(floatArrayOf(values[0], values[1], values[2], values[3], values[4], values[5], 0f, 0f, 1f))
    }
    Canvas(output).drawBitmap(this, matrix, uprightPaint)
    recycle()
    return output
}

/** 선택한 얼굴만 남긴 등록 이미지를 만듭니다. 좌표는 [FaceSelectionGeometry.registrationPlan]이 정합니다. */
internal object FaceRegistrationImage {
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * 바로 선 프레임 [bitmap]에서 선택한 얼굴 주위를 2배 정사각형으로 잘라 500px로 맞춘 이미지입니다. 다른 얼굴은
     * 검게 가리고 프레임 밖은 서버의 정사각형 여백처럼 검게 채웁니다. 얼굴이 작거나 프레임이 작으면 null입니다.
     * [faces] 좌표는 [bitmap] 기준 정규화 값입니다.
     */
    fun crop(bitmap: Bitmap, faces: List<FaceSelectionCandidate>, faceIndex: Int): Bitmap? {
        val plan = FaceSelectionGeometry.registrationPlan(bitmap.width, bitmap.height, faces, faceIndex) ?: return null
        val size = FaceSelectionGeometry.REGISTRATION_SIZE
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.scale(plan.scale, plan.scale)
        canvas.translate(-plan.crop.left, -plan.crop.top)
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        draw(canvas, bitmap, plan)
        return output
    }

    /** [bitmap] 안에서 다시 찾은 다른 얼굴을 가린 새 이미지입니다. 좌표는 [bitmap] 기준 정규화 값입니다. */
    fun maskOthers(bitmap: Bitmap, hiding: List<FaceBox>, keeping: FaceBox): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Registration image copy failed")
        draw(Canvas(output), bitmap, FaceSelectionGeometry.maskPlan(bitmap.width, bitmap.height, hiding, keeping))
        return output
    }

    private fun draw(canvas: Canvas, source: Bitmap, plan: RegistrationImagePlan) {
        plan.masks.forEach { canvas.drawRect(it.toRectF(), blackPaint) }
        val keep = plan.keep ?: return
        val keepSource = Rect(keep.left.toInt(), keep.top.toInt(), keep.right.toInt(), keep.bottom.toInt())
        canvas.drawBitmap(source, keepSource, RectF(keepSource), bitmapPaint)
    }
}
