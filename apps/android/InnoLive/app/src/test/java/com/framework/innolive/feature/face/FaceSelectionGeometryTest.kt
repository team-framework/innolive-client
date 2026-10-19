package com.framework.innolive.feature.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSelectionGeometryTest {
    @Test
    fun candidatesNormalizeBoxesAndRequireRegistrableSize() {
        val candidates = FaceSelectionGeometry.candidates(
            boxes = listOf(
                FaceBox.ofSize(left = 540, top = 480, width = 200, height = 240),
                FaceBox.ofSize(left = 100, top = 100, width = 80, height = 120),
            ),
            imageWidth = 1080,
            imageHeight = 1920,
        )

        assertEquals(2, candidates.size)
        assertEquals(FaceBox(0.5f, 0.25f, 740f / 1080f, 720f / 1920f), candidates[0].rect)
        assertTrue(candidates[0].isSelectable)
        // 짧은 변이 100px보다 작은 얼굴은 등록 임베딩 품질이 낮아 선택할 수 없습니다.
        assertFalse(candidates[1].isSelectable)
    }

    @Test
    fun candidatesOutsideTheFrameAreNotSelectable() {
        val candidates = FaceSelectionGeometry.candidates(
            boxes = listOf(FaceBox(-150f, 100f, 50f, 300f)),
            imageWidth = 1080,
            imageHeight = 1920,
        )

        assertFalse(candidates.single().isSelectable)
    }

    @Test
    fun displayRectFillsAnAspectMatchedView() {
        val rect = FaceSelectionGeometry.displayRect(
            normalized = FaceBox(0.25f, 0.5f, 0.75f, 0.75f),
            imageWidth = 1080,
            imageHeight = 1920,
            viewWidth = 540f,
            viewHeight = 960f,
        )

        assertEquals(FaceBox(135f, 480f, 405f, 720f), rect)
    }

    @Test
    fun displayRectLetterboxesAWiderView() {
        val rect = FaceSelectionGeometry.displayRect(
            normalized = FaceBox(0f, 0f, 0.25f, 0.5f),
            imageWidth = 1080,
            imageHeight = 1920,
            viewWidth = 1080f,
            viewHeight = 960f,
        )

        // 세로 프레임은 540x960으로 줄어 가로 가운데(270px 여백)에 놓입니다. 전면 카메라 프레임은 이미 반전되어 있습니다.
        assertEquals(FaceBox(270f, 0f, 270f + 135f, 480f), rect)
        assertEquals(
            FacePoint(270f + 270f, 480f),
            FaceSelectionGeometry.displayPoint(FacePoint(0.5f, 0.5f), 1080, 1920, 1080f, 960f),
        )
    }

    @Test
    fun uprightTransformRotatesClockwiseFor90And270() {
        // 4x2 센서 프레임을 세우면 2x4가 됩니다.
        assertEquals(2 to 4, FaceSelectionGeometry.uprightSize(4, 2, 90))
        assertEquals(2 to 4, FaceSelectionGeometry.uprightSize(4, 2, 270))
        assertEquals(4 to 2, FaceSelectionGeometry.uprightSize(4, 2, 180))

        val clockwise = FaceSelectionGeometry.uprightTransform(4, 2, 90, mirrored = false)
        // 시계 방향 90도: 왼쪽 위는 오른쪽 위로, 오른쪽 위는 오른쪽 아래로 갑니다.
        assertEquals(FacePoint(2f, 0f), clockwise.map(0f, 0f))
        assertEquals(FacePoint(2f, 4f), clockwise.map(4f, 0f))
        assertEquals(FacePoint(0f, 4f), clockwise.map(4f, 2f))

        val counterClockwise = FaceSelectionGeometry.uprightTransform(4, 2, 270, mirrored = false)
        // 시계 방향 270도: 왼쪽 위는 왼쪽 아래로, 오른쪽 위는 왼쪽 위로 갑니다.
        assertEquals(FacePoint(0f, 4f), counterClockwise.map(0f, 0f))
        assertEquals(FacePoint(0f, 0f), counterClockwise.map(4f, 0f))
        assertEquals(FacePoint(2f, 0f), counterClockwise.map(4f, 2f))
    }

    @Test
    fun mirroredTransformFlipsTheUprightFrameLikeTheFrontPreview() {
        val mirrored = FaceSelectionGeometry.uprightTransform(4, 2, 270, mirrored = true)

        // 세운 뒤 좌우를 뒤집으므로 270도에서 왼쪽 아래로 가던 점이 오른쪽 아래로 갑니다.
        assertEquals(FacePoint(2f, 4f), mirrored.map(0f, 0f))
        assertEquals(FacePoint(2f, 0f), mirrored.map(4f, 0f))
        assertEquals(
            FacePoint(1f, 2f),
            FaceSelectionGeometry.uprightTransform(4, 2, 0, mirrored = true).map(3f, 2f),
        )
    }

    @Test
    fun registrationPlanFollowsAFaceAfterMirroring() {
        // 1000x1000 센서 프레임 왼쪽(x 100~300)의 얼굴은 반전된 전면 프레임에서 오른쪽(x 700~900)에 있습니다.
        val transform = FaceSelectionGeometry.uprightTransform(1000, 1000, 0, mirrored = true)
        val topLeft = transform.map(100f, 400f)
        val bottomRight = transform.map(300f, 600f)
        val face = FaceBox(
            minOf(topLeft.x, bottomRight.x) / 1000f,
            minOf(topLeft.y, bottomRight.y) / 1000f,
            maxOf(topLeft.x, bottomRight.x) / 1000f,
            maxOf(topLeft.y, bottomRight.y) / 1000f,
        )

        val plan = checkNotNull(
            FaceSelectionGeometry.registrationPlan(
                imageWidth = 1000,
                imageHeight = 1000,
                faces = listOf(FaceSelectionCandidate(face, isSelectable = true)),
                faceIndex = 0,
            ),
        )

        assertEquals(800f, plan.crop.centerX, 0.001f)
        assertEquals(500f, plan.crop.centerY, 0.001f)
    }

    @Test
    fun registrationPlanCropsTwiceTheFaceAndMasksOthers() {
        val plan = checkNotNull(
            FaceSelectionGeometry.registrationPlan(
                imageWidth = 1000,
                imageHeight = 1000,
                faces = listOf(
                    FaceSelectionCandidate(FaceBox(0.1f, 0.1f, 0.2f, 0.2f), isSelectable = false),
                    FaceSelectionCandidate(FaceBox(0.4f, 0.4f, 0.6f, 0.6f), isSelectable = true),
                ),
                faceIndex = 1,
            ),
        )

        assertBox(FaceBox(300f, 300f, 700f, 700f), plan.crop)
        assertEquals(1.25f, plan.scale, 0.0001f)
        // 다른 얼굴은 1.3배 키워 가리고, 선택한 얼굴은 원본으로 되돌립니다.
        assertBox(FaceBox(85f, 85f, 215f, 215f), plan.masks.single())
        assertBox(FaceBox(400f, 400f, 600f, 600f), checkNotNull(plan.keep))
        // 선택한 얼굴의 가운데는 500px 등록 이미지의 가운데에 옵니다.
        assertEquals(250f, (checkNotNull(plan.keep).centerX - plan.crop.left) * plan.scale, 0.001f)
    }

    @Test
    fun registrationPlanRejectsUnselectableFacesAndSmallFrames() {
        val selectable = listOf(FaceSelectionCandidate(FaceBox(0.4f, 0.4f, 0.6f, 0.6f), isSelectable = true))

        assertNull(FaceSelectionGeometry.registrationPlan(1000, 1000, selectable, faceIndex = 1))
        assertNull(FaceSelectionGeometry.registrationPlan(480, 1000, selectable, faceIndex = 0))
        assertNull(
            FaceSelectionGeometry.registrationPlan(
                1000,
                1000,
                listOf(selectable.single().copy(isSelectable = false)),
                faceIndex = 0,
            ),
        )
    }

    @Test
    fun maskPlanKeepsTheSelectedFaceInsideTheImage() {
        val alone = FaceSelectionGeometry.maskPlan(500, 500, hiding = emptyList(), keeping = FaceBox(0.4f, 0.4f, 0.6f, 0.6f))
        assertTrue(alone.masks.isEmpty())
        assertNull(alone.keep)

        val edge = FaceSelectionGeometry.maskPlan(
            imageWidth = 500,
            imageHeight = 500,
            hiding = listOf(FaceBox(0.6f, 0.6f, 0.8f, 0.8f)),
            keeping = FaceBox(-0.1f, 0.4f, 0.3f, 0.6f),
        )
        assertBox(FaceBox(0f, 200f, 150f, 300f), checkNotNull(edge.keep))
        assertEquals(1, edge.masks.size)
    }

    @Test
    fun contoursAttachOnlyToTheOverlappingBox() {
        val points = listOf(FacePoint(0.1f, 0.2f))
        val contours = FaceSelectionGeometry.matchContours(
            boxes = listOf(FaceBox(0f, 0f, 100f, 100f), FaceBox(300f, 0f, 400f, 100f)),
            contourFaces = listOf(
                FaceBox(305f, 5f, 405f, 105f) to points,
                FaceBox(700f, 700f, 800f, 800f) to listOf(FacePoint(0.9f, 0.9f)),
            ),
        )

        assertEquals(listOf(emptyList(), points), contours)
    }

    @Test
    fun registrationCropIsASquareTwiceTheFaceAroundItsCenter() {
        val face = FaceSelectionGeometry.pixelRect(
            normalized = FaceBox(0.4f, 0.4f, 0.6f, 0.55f),
            imageWidth = 1000,
            imageHeight = 1000,
        )

        val crop = FaceSelectionGeometry.registrationCropRect(face)

        assertEquals(400f, crop.width, 0.001f)
        assertEquals(400f, crop.height, 0.001f)
        assertEquals(face.centerX, crop.centerX, 0.001f)
        assertEquals(face.centerY, crop.centerY, 0.001f)
    }

    @Test
    fun pixelRectScalesAroundTheFaceCenter() {
        val masked = FaceSelectionGeometry.pixelRect(
            normalized = FaceBox(0.1f, 0.1f, 0.3f, 0.3f),
            imageWidth = 1000,
            imageHeight = 1000,
            scale = FaceSelectionGeometry.MASKED_FACE_SCALE,
        )

        assertEquals(200f, masked.centerX, 0.001f)
        assertEquals(200f, masked.centerY, 0.001f)
        assertEquals(260f, masked.width, 0.001f)
    }

    @Test
    fun centerCheckUsesTheRegistrationImageMiddle() {
        assertTrue(FaceSelectionGeometry.containsCenter(FaceBox(0.3f, 0.3f, 0.7f, 0.7f)))
        assertFalse(FaceSelectionGeometry.containsCenter(FaceBox(0.6f, 0.6f, 0.9f, 0.9f)))
        assertEquals(
            FaceBox(0.1f, 0.2f, 0.3f, 0.4f),
            FaceSelectionGeometry.normalized(FaceBox(50f, 100f, 150f, 200f), 500, 500),
        )
    }

    private fun FloatArray.map(x: Float, y: Float): FacePoint =
        FacePoint(this[0] * x + this[1] * y + this[2], this[3] * x + this[4] * y + this[5])

    private fun assertBox(expected: FaceBox, actual: FaceBox) {
        assertEquals(expected.left, actual.left, 0.001f)
        assertEquals(expected.top, actual.top, 0.001f)
        assertEquals(expected.right, actual.right, 0.001f)
        assertEquals(expected.bottom, actual.bottom, 0.001f)
    }
}
