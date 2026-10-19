package com.framework.innolive.feature.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FaceOutlineTest {
    @Test
    fun faceWithoutContourUsesTheIosEllipse() {
        val face = FaceSelectionCandidate(FaceBox(0.4f, 0.4f, 0.6f, 0.6f), isSelectable = true)

        val shape = FaceOutline.shape(face, imageWidth = 1000, imageHeight = 1000, viewWidth = 500f, viewHeight = 500f)

        // 화면 상자 (200, 200, 300, 300)을 1.15배 키운 타원입니다.
        val ellipse = shape as FaceOutlineShape.Ellipse
        assertEquals(192.5f, ellipse.bounds.left, 0.001f)
        assertEquals(307.5f, ellipse.bounds.bottom, 0.001f)
    }

    @Test
    fun jawlineTakesTheLowerHalfFromEarToEar() {
        val contour = circle(startDegrees = -90f)

        val jaw = FaceOutline.jawline(contour)

        // 오른쪽 귀 높이에서 시작해 턱 끝을 지나 왼쪽 귀 높이에서 끝나며, 원래 순서대로 이어집니다.
        assertTrue(jaw.size in 17..21)
        assertTrue(jaw.first().x > 0.65f && abs(jaw.first().y - 0.5f) < 0.05f)
        assertTrue(jaw.last().x < 0.35f && abs(jaw.last().y - 0.5f) < 0.05f)
        assertTrue(jaw.any { abs(it.x - 0.5f) < 0.001f && abs(it.y - 0.7f) < 0.001f })
        val start = contour.indexOf(jaw.first())
        jaw.forEachIndexed { offset, point -> assertEquals(contour[(start + offset) % contour.size], point) }
    }

    @Test
    fun jawlineDoesNotDependOnWhereTheContourStarts() {
        val fromTop = FaceOutline.jawline(circle(startDegrees = -90f))
        val fromChin = FaceOutline.jawline(circle(startDegrees = 90f))

        assertEquals(fromTop.size, fromChin.size)
        assertEquals(fromTop.first().x, fromChin.first().x, 0.001f)
        assertEquals(fromTop.last().x, fromChin.last().x, 0.001f)
    }

    @Test
    fun contourShapeClosesTheJawWithAForeheadCurve() {
        val face = FaceSelectionCandidate(
            rect = FaceBox(0.3f, 0.3f, 0.7f, 0.7f),
            isSelectable = true,
            contour = circle(startDegrees = -90f),
        )

        val shape = FaceOutline.shape(face, imageWidth = 1000, imageHeight = 1000, viewWidth = 1000f, viewHeight = 1000f)

        val contour = shape as FaceOutlineShape.Contour
        // 턱선은 얼굴 가운데(500, 500)에서 1.1배 바깥으로 밀려납니다. 턱 끝 (500, 700)은 (500, 720)이 됩니다.
        assertTrue(contour.jaw.any { abs(it.x - 500f) < 0.01f && abs(it.y - 720f) < 0.01f })
        val start = minOf(contour.jaw.first().y, contour.jaw.last().y)
        assertEquals(start - 400f * 0.6f, contour.domeTop, 0.01f)
        // 이마 곡선은 제어점 높이의 3/4까지 올라가므로 윤곽 영역도 턱선보다 위로 넓어집니다.
        assertEquals(start - 400f * 0.6f * 0.75f, contour.bounds.top, 0.01f)
        assertEquals(720f, contour.bounds.bottom, 0.01f)
    }

    @Test
    fun tooFewContourPointsFallBackToTheEllipse() {
        val face = FaceSelectionCandidate(
            rect = FaceBox(0.3f, 0.3f, 0.7f, 0.7f),
            isSelectable = true,
            contour = listOf(FacePoint(0.5f, 0.3f), FacePoint(0.7f, 0.5f), FacePoint(0.5f, 0.7f)),
        )

        assertTrue(FaceOutline.shape(face, 1000, 1000, 1000f, 1000f) is FaceOutlineShape.Ellipse)
    }

    /** 가운데 (0.5, 0.5), 반지름 0.2인 36점 원을 화면 기준 시계 방향으로 돕니다. */
    private fun circle(startDegrees: Float): List<FacePoint> = (0 until 36).map { index ->
        val radians = Math.toRadians((startDegrees + index * 10f).toDouble())
        FacePoint((0.5 + 0.2 * cos(radians)).toFloat(), (0.5 + 0.2 * sin(radians)).toFloat())
    }
}
