package com.framework.innolive.feature.face

import kotlin.math.max
import kotlin.math.min

/** 안드로이드 그래픽 타입 없이 다루는 사각형입니다. 단위 테스트가 좌표 규칙을 그대로 검증할 수 있습니다. */
internal data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    companion object {
        fun ofSize(left: Int, top: Int, width: Int, height: Int): FaceBox =
            FaceBox(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
    }
}

/** 안드로이드 그래픽 타입 없이 다루는 점입니다. */
internal data class FacePoint(val x: Float, val y: Float)

/**
 * 원본 프레임에서 찾은 얼굴 하나입니다. [rect]는 왼쪽 위 원점의 정규화 좌표입니다.
 * [contour]는 같은 좌표의 얼굴 윤곽점이며, ML Kit이 윤곽을 찾지 못한 얼굴은 비어 있습니다.
 */
internal data class FaceSelectionCandidate(
    val rect: FaceBox,
    val isSelectable: Boolean,
    val contour: List<FacePoint> = emptyList(),
)

/**
 * 등록 이미지를 그리는 순서입니다. 좌표는 모두 원본 픽셀이며, [crop] 영역을 [scale]배 해 500px 정사각형에
 * 그린 뒤 [masks]를 검게 칠하고 [keep] 영역만 원본으로 되돌립니다. 이웃의 가림이 선택한 얼굴을 덮지 않게 합니다.
 */
internal data class RegistrationImagePlan(
    val crop: FaceBox,
    val scale: Float,
    val masks: List<FaceBox>,
    val keep: FaceBox?,
)

/** 얼굴 감지·미리보기 오버레이·등록용 자르기가 함께 쓰는 좌표 규칙입니다. iOS `FaceSelectionGeometry`와 같습니다. */
internal object FaceSelectionGeometry {
    /** 원본 픽셀 기준 이 값보다 작은 얼굴은 등록 임베딩 품질이 낮아 선택할 수 없습니다. */
    const val MINIMUM_FACE_SIDE = 100f

    /** 얼굴 인식기가 쓰는 1.5배 정사각형이 자른 이미지 안에 들어오도록 2배로 자릅니다. */
    const val REGISTRATION_CROP_SCALE = 2f

    /** 다른 사람의 머리카락·턱까지 덮도록 얼굴 상자를 키워 가립니다. */
    const val MASKED_FACE_SCALE = 1.3f

    /** 윤곽점이 없는 얼굴에 그리는 타원의 배율입니다. iOS `FaceOutline`의 타원과 같습니다. */
    const val OUTLINE_SCALE = 1.15f

    /** 윤곽선이 볼을 가로지르지 않고 얼굴 가장자리 바로 바깥을 지나도록 키우는 배율입니다. */
    const val OUTLINE_MARGIN = 1.1f

    /** 등록 이미지 한 변 픽셀 수입니다. 서버·온디바이스 등록이 모두 500px 정사각형을 받습니다. */
    const val REGISTRATION_SIZE = 500

    /** 픽셀 단위 얼굴 상자를 정규화하고, 등록할 수 있을 만큼 크고 화면 안에 중심이 있는 얼굴을 표시합니다. */
    fun candidates(
        boxes: List<FaceBox>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<FaceSelectionCandidate> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        return boxes.map { box ->
            val side = min(box.width, box.height)
            val rect = FaceBox(
                box.left / imageWidth,
                box.top / imageHeight,
                box.right / imageWidth,
                box.bottom / imageHeight,
            )
            FaceSelectionCandidate(
                rect = rect,
                isSelectable = side >= MINIMUM_FACE_SIDE &&
                    rect.centerX in 0f..1f && rect.centerY in 0f..1f,
            )
        }
    }

    /**
     * 정규화 사각형을 [viewWidth]x[viewHeight] 화면에 그릴 위치로 바꿉니다. 프레임은 화면에 비율을
     * 유지한 채 가운데 맞춤(fit center)으로 놓입니다. 전면 카메라 프레임은 이미 미리보기처럼 좌우 반전되어 있습니다.
     */
    fun displayRect(
        normalized: FaceBox,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): FaceBox {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
            return FaceBox(0f, 0f, 0f, 0f)
        }
        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        val originX = (viewWidth - displayedWidth) / 2f
        val originY = (viewHeight - displayedHeight) / 2f
        return FaceBox(
            originX + normalized.left * displayedWidth,
            originY + normalized.top * displayedHeight,
            originX + normalized.right * displayedWidth,
            originY + normalized.bottom * displayedHeight,
        )
    }

    /** [displayRect]와 같은 규칙으로 정규화 점을 화면 좌표로 바꿉니다. */
    fun displayPoint(
        normalized: FacePoint,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): FacePoint {
        val rect = displayRect(
            FaceBox(normalized.x, normalized.y, normalized.x, normalized.y),
            imageWidth,
            imageHeight,
            viewWidth,
            viewHeight,
        )
        return FacePoint(rect.left, rect.top)
    }

    /** 정규화 사각형을 픽셀 사각형으로 바꾸고 가운데를 기준으로 [scale]배 키웁니다. */
    fun pixelRect(normalized: FaceBox, imageWidth: Int, imageHeight: Int, scale: Float = 1f): FaceBox {
        val width = normalized.width * imageWidth * scale
        val height = normalized.height * imageHeight * scale
        val centerX = normalized.centerX * imageWidth
        val centerY = normalized.centerY * imageHeight
        return FaceBox(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
    }

    /** 얼굴 픽셀 사각형을 둘러싼 등록용 정사각형입니다. 프레임 밖으로 나갈 수 있으며 그 부분은 검게 채웁니다. */
    fun registrationCropRect(face: FaceBox): FaceBox {
        val side = max(face.width, face.height) * REGISTRATION_CROP_SCALE
        val centerX = face.centerX
        val centerY = face.centerY
        return FaceBox(centerX - side / 2f, centerY - side / 2f, centerX + side / 2f, centerY + side / 2f)
    }

    /**
     * 바로 선 [imageWidth]x[imageHeight] 프레임에서 [faceIndex] 얼굴을 등록할 이미지의 그리기 순서입니다.
     * 고를 수 없는 얼굴이거나 프레임이 등록 크기보다 작으면 null입니다. iOS `registrationImage`와 같습니다.
     */
    fun registrationPlan(
        imageWidth: Int,
        imageHeight: Int,
        faces: List<FaceSelectionCandidate>,
        faceIndex: Int,
    ): RegistrationImagePlan? {
        val face = faces.getOrNull(faceIndex) ?: return null
        if (!face.isSelectable || min(imageWidth, imageHeight) < REGISTRATION_SIZE) return null
        val crop = registrationCropRect(pixelRect(face.rect, imageWidth, imageHeight))
        if (crop.width <= 0f) return null
        val others = faces.filterIndexed { index, _ -> index != faceIndex }.map { it.rect }
        val masking = maskPlan(imageWidth, imageHeight, others, face.rect)
        return RegistrationImagePlan(crop, REGISTRATION_SIZE / crop.width, masking.masks, masking.keep)
    }

    /** [hiding] 얼굴을 가리고 [keeping] 얼굴을 되살리는 순서입니다. 크기 조정 없이 같은 이미지에 그립니다. */
    fun maskPlan(imageWidth: Int, imageHeight: Int, hiding: List<FaceBox>, keeping: FaceBox): RegistrationImagePlan {
        val whole = FaceBox(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        if (hiding.isEmpty()) return RegistrationImagePlan(whole, 1f, emptyList(), null)
        val masks = hiding.map { pixelRect(it, imageWidth, imageHeight, MASKED_FACE_SCALE) }
        val selected = pixelRect(keeping, imageWidth, imageHeight)
        val keep = FaceBox(
            Math.round(selected.left).coerceIn(0, imageWidth).toFloat(),
            Math.round(selected.top).coerceIn(0, imageHeight).toFloat(),
            Math.round(selected.right).coerceIn(0, imageWidth).toFloat(),
            Math.round(selected.bottom).coerceIn(0, imageHeight).toFloat(),
        ).takeIf { it.width > 0f && it.height > 0f }
        return RegistrationImagePlan(whole, 1f, masks, keep)
    }

    fun scaled(rect: FaceBox, scale: Float): FaceBox {
        val dx = rect.width * (scale - 1f) / 2f
        val dy = rect.height * (scale - 1f) / 2f
        return FaceBox(rect.left - dx, rect.top - dy, rect.right + dx, rect.bottom + dy)
    }

    /** 픽셀 사각형을 [imageWidth]x[imageHeight] 기준 정규화 좌표로 바꿉니다. */
    fun normalized(box: FaceBox, imageWidth: Int, imageHeight: Int): FaceBox = FaceBox(
        box.left / imageWidth,
        box.top / imageHeight,
        box.right / imageWidth,
        box.bottom / imageHeight,
    )

    /** 정규화 사각형이 이미지 가운데를 포함하는지 봅니다. 등록 이미지의 선택 얼굴은 항상 가운데에 있습니다. */
    fun containsCenter(normalized: FaceBox): Boolean = normalized.contains(0.5f, 0.5f)

    /** 카메라 원본 [width]x[height]를 [rotationDegrees]만큼 시계 방향으로 세운 크기(가로, 세로)입니다. */
    fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (normalizedRotation(rotationDegrees) % 180 == 0) width to height else height to width

    /**
     * 카메라 원본 좌표 (x, y)를 바로 선 프레임 좌표 (u, v)로 옮기는 아핀 변환 `[a, b, c, d, e, f]`입니다.
     * `u = a·x + b·y + c`, `v = d·x + e·y + f`이며, 시계 방향으로 [rotationDegrees]만큼 돌린 뒤
     * [mirrored]면 전면 카메라 미리보기처럼 좌우를 뒤집습니다. iOS `leftMirrored` 방향과 같습니다.
     */
    fun uprightTransform(width: Int, height: Int, rotationDegrees: Int, mirrored: Boolean): FloatArray {
        val w = width.toFloat()
        val h = height.toFloat()
        val values = when (normalizedRotation(rotationDegrees)) {
            90 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f)
            180 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
            270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        }
        if (mirrored) {
            val uprightWidth = uprightSize(width, height, rotationDegrees).first.toFloat()
            values[0] = -values[0]
            values[1] = -values[1]
            values[2] = uprightWidth - values[2]
        }
        return values
    }

    /**
     * 윤곽 감지기가 찾은 얼굴을 상자 감지기 얼굴과 짝지어 [boxes] 순서대로 윤곽점을 돌려줍니다. 두 상자의
     * 겹침(IoU)이 [MINIMUM_CONTOUR_OVERLAP] 이상인 가장 가까운 상자 하나에만 붙이고, 짝이 없으면 비웁니다.
     */
    fun matchContours(
        boxes: List<FaceBox>,
        contourFaces: List<Pair<FaceBox, List<FacePoint>>>,
    ): List<List<FacePoint>> {
        val contours = MutableList(boxes.size) { emptyList<FacePoint>() }
        for ((contourBox, points) in contourFaces) {
            val match = boxes.indices
                .filter { contours[it].isEmpty() }
                .maxByOrNull { overlap(boxes[it], contourBox) }
                ?: continue
            if (overlap(boxes[match], contourBox) >= MINIMUM_CONTOUR_OVERLAP) contours[match] = points
        }
        return contours
    }

    private const val MINIMUM_CONTOUR_OVERLAP = 0.3f

    private fun overlap(a: FaceBox, b: FaceBox): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (width <= 0f || height <= 0f) return 0f
        val intersection = width * height
        return intersection / (a.width * a.height + b.width * b.height - intersection)
    }

    private fun normalizedRotation(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360
}

/** 선택 화면에 그릴 얼굴 윤곽입니다. 좌표는 화면 픽셀입니다. */
internal sealed interface FaceOutlineShape {
    val bounds: FaceBox

    /** 윤곽점이 없을 때 얼굴 상자를 감싸는 타원입니다. */
    data class Ellipse(override val bounds: FaceBox) : FaceOutlineShape

    /**
     * 귀에서 턱을 지나 반대쪽 귀까지의 [jaw] 선을 이마 위 곡선으로 닫은 모양입니다. 곡선은 [jaw]의 끝점에서
     * 시작점으로 가며, 두 제어점의 세로 위치가 [domeTop]입니다. iOS `FaceOutline.path`와 같습니다.
     */
    data class Contour(
        val jaw: List<FacePoint>,
        val domeTop: Float,
        override val bounds: FaceBox,
    ) : FaceOutlineShape
}

/** 얼굴 모양 윤곽선입니다. iOS `FaceOutline`처럼 턱선에 이마 곡선을 이어 닫고, 윤곽점이 없으면 타원을 씁니다. */
internal object FaceOutline {
    /** iOS Vision 턱선처럼 다섯 점 이상이어야 턱 모양을 그립니다. */
    private const val MINIMUM_JAW_POINTS = 5

    /** 턱선 끝에서 이마 곡선 제어점까지의 높이입니다. 얼굴 상자 높이에 대한 비율입니다. */
    private const val DOME_HEIGHT = 0.6f

    fun shape(
        face: FaceSelectionCandidate,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): FaceOutlineShape {
        val box = FaceSelectionGeometry.displayRect(face.rect, imageWidth, imageHeight, viewWidth, viewHeight)
        val jaw = jawline(face.contour).map { point ->
            val display = FaceSelectionGeometry.displayPoint(point, imageWidth, imageHeight, viewWidth, viewHeight)
            FacePoint(
                box.centerX + (display.x - box.centerX) * FaceSelectionGeometry.OUTLINE_MARGIN,
                box.centerY + (display.y - box.centerY) * FaceSelectionGeometry.OUTLINE_MARGIN,
            )
        }
        if (jaw.size < MINIMUM_JAW_POINTS) {
            return FaceOutlineShape.Ellipse(FaceSelectionGeometry.scaled(box, FaceSelectionGeometry.OUTLINE_SCALE))
        }
        val first = jaw.first()
        val last = jaw.last()
        val start = min(first.y, last.y)
        val domeTop = start - box.height * DOME_HEIGHT
        // 두 제어점이 같은 높이인 3차 곡선은 가운데에서 끝점과 제어점 사이 3/4 지점까지 올라갑니다.
        val top = min(jaw.minOf { it.y }, start + (domeTop - start) * 0.75f)
        val bounds = FaceBox(
            left = jaw.minOf { it.x },
            top = top,
            right = jaw.maxOf { it.x },
            bottom = jaw.maxOf { it.y },
        )
        return FaceOutlineShape.Contour(jaw, domeTop, bounds)
    }

    /**
     * ML Kit 얼굴 윤곽(이마부터 한 바퀴 도는 닫힌 점들)에서 아래쪽 절반을 이어진 순서대로 꺼냅니다.
     * iOS Vision 턱선처럼 한쪽 귀 높이에서 턱을 지나 반대쪽 귀 높이까지 갑니다. 시작 위치와 방향에 상관없습니다.
     */
    fun jawline(contour: List<FacePoint>): List<FacePoint> {
        if (contour.size < MINIMUM_JAW_POINTS) return emptyList()
        val middle = (contour.minOf { it.y } + contour.maxOf { it.y }) / 2f
        val lower = contour.map { it.y >= middle }
        val count = contour.size
        val start = (0 until count).firstOrNull { index -> lower[index] && !lower[(index - 1 + count) % count] }
            ?: return emptyList()
        val jaw = mutableListOf<FacePoint>()
        var index = start
        while (lower[index] && jaw.size < count) {
            jaw += contour[index]
            index = (index + 1) % count
        }
        return jaw
    }
}
