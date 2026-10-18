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

/** 원본 프레임에서 찾은 얼굴 하나입니다. [rect]는 왼쪽 위 원점의 정규화 좌표입니다. */
internal data class FaceSelectionCandidate(
    val rect: FaceBox,
    val isSelectable: Boolean,
)

/** 얼굴 감지·미리보기 오버레이·등록용 자르기가 함께 쓰는 좌표 규칙입니다. iOS `FaceSelectionGeometry`와 같습니다. */
internal object FaceSelectionGeometry {
    /** 원본 픽셀 기준 이 값보다 작은 얼굴은 등록 임베딩 품질이 낮아 선택할 수 없습니다. */
    const val MINIMUM_FACE_SIDE = 100f

    /** 얼굴 인식기가 쓰는 1.5배 정사각형이 자른 이미지 안에 들어오도록 2배로 자릅니다. */
    const val REGISTRATION_CROP_SCALE = 2f

    /** 다른 사람의 머리카락·턱까지 덮도록 얼굴 상자를 키워 가립니다. */
    const val MASKED_FACE_SCALE = 1.3f

    /** 감지 상자는 얼굴에 붙어 있으므로 윤곽은 머리 전체를 감싸도록 키웁니다. */
    const val OUTLINE_SCALE = 1.25f

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
     * 유지한 채 가운데 맞춤(fit center)으로 놓이고, [mirrored]면 전면 카메라 미리보기처럼 좌우를 뒤집습니다.
     */
    fun displayRect(
        normalized: FaceBox,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        mirrored: Boolean = false,
    ): FaceBox {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
            return FaceBox(0f, 0f, 0f, 0f)
        }
        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        val originX = (viewWidth - displayedWidth) / 2f
        val originY = (viewHeight - displayedHeight) / 2f
        val left = if (mirrored) 1f - normalized.right else normalized.left
        val right = if (mirrored) 1f - normalized.left else normalized.right
        return FaceBox(
            originX + left * displayedWidth,
            originY + normalized.top * displayedHeight,
            originX + right * displayedWidth,
            originY + normalized.bottom * displayedHeight,
        )
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
}
