package com.framework.innolive.feature.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun displayRectLetterboxesAWiderViewAndMirrorsTheFrontCamera() {
        val rect = FaceSelectionGeometry.displayRect(
            normalized = FaceBox(0f, 0f, 0.25f, 0.5f),
            imageWidth = 1080,
            imageHeight = 1920,
            viewWidth = 1080f,
            viewHeight = 960f,
            mirrored = true,
        )

        // 세로 프레임은 540x960으로 줄어 가로 가운데(270px 여백)에 놓이고, 좌우 반전으로 오른쪽 가장자리에 붙습니다.
        assertEquals(FaceBox(270f + 405f, 0f, 270f + 540f, 480f), rect)
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
}
