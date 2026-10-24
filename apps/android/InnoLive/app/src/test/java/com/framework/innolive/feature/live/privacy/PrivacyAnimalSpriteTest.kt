package com.framework.innolive.feature.live.privacy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PrivacyAnimalSpriteTest {
    private fun atlas(name: String): PrivacyAnimalAtlas {
        val json = JSONObject(File("src/main/assets/$name.json").readText())
        return PrivacyAnimalAtlas.parse(json, json.getInt("width"), json.getInt("height"))
    }

    @Test
    fun bundledAtlasesCoverZeroToNinetyDegreesWithMirroring() {
        for (name in listOf("rabbit", "owl")) {
            val atlas = atlas(name)
            assertTrue(atlas.mirrorNegativeYaw)
            assertEquals((0..90 step 10).map(Int::toFloat), atlas.views.map { it.yaw })
        }
    }

    @Test
    fun placementKeepsTheAnimalAspectForANarrowSideFaceBox() {
        val atlas = atlas("rabbit")
        val front = checkNotNull(atlas.place(PrivacyAnimalFace(100f, 100f, 300f, 340f)))
        val side = checkNotNull(atlas.place(PrivacyAnimalFace(100f, 100f, 220f, 340f, yaw = 90f)))
        val cell = atlas.views.first().cell
        // The canvas keeps the cell aspect: a narrower box never squeezes the sprite horizontally.
        assertEquals(cell.width.toFloat() / cell.height, side.canvasWidth / side.canvasHeight, 1e-4f)
        assertEquals(front.canvasHeight, side.canvasHeight, front.canvasHeight * .2f)
        assertEquals(90f, side.from.yaw)
        assertEquals(90f, side.to.yaw)
    }

    @Test
    fun placementCoversTheFaceBox() {
        val atlas = atlas("owl")
        val face = PrivacyAnimalFace(40f, 60f, 200f, 260f, yaw = 33f)
        val place = checkNotNull(atlas.place(face))
        val a = place.from.face; val b = place.to.face
        val width = (a.width + (b.width - a.width) * place.blend) * place.canvasWidth
        val height = (a.height + (b.height - a.height) * place.blend) * place.canvasHeight
        assertTrue(width >= 160f - 1e-3f && height >= 200f - 1e-3f)
        assertEquals(120f, place.centerX, 0f)
        assertEquals(160f, place.centerY, 0f)
    }

    @Test
    fun negativeYawMirrorsTheMatchingPositiveView() {
        val atlas = atlas("rabbit")
        val right = checkNotNull(atlas.place(PrivacyAnimalFace(0f, 0f, 100f, 120f, yaw = 60f)))
        val left = checkNotNull(atlas.place(PrivacyAnimalFace(0f, 0f, 100f, 120f, yaw = -60f)))
        assertFalse(right.mirrored)
        assertTrue(left.mirrored)
        assertEquals(right.from.yaw, left.from.yaw)
        assertEquals(1f - right.anchorX, left.anchorX, 1e-6f)
    }

    @Test
    fun crossFadeOnlyNearTheMidpointBetweenViews() {
        val atlas = atlas("rabbit")
        fun blend(yaw: Float) = checkNotNull(atlas.place(PrivacyAnimalFace(0f, 0f, 100f, 120f, yaw = yaw))).blend
        assertEquals(0f, blend(24.4f), 0f)
        assertEquals(.5f, blend(25f), 1e-5f)
        assertEquals(1f, blend(25.6f), 0f)
        assertEquals(90f, checkNotNull(atlas.place(PrivacyAnimalFace(0f, 0f, 100f, 120f, yaw = 120f))).from.yaw)
    }

    @Test
    fun versionOneSpriteLoadsAsOneFrontView() {
        val json = JSONObject("""{"width":1024,"height":1024,"faceRect":[0.25,0.42,0.5,0.5]}""")
        val atlas = PrivacyAnimalAtlas.parse(json, 1024, 1024)
        val place = checkNotNull(atlas.place(PrivacyAnimalFace(0f, 0f, 100f, 100f, yaw = -45f)))
        assertFalse(place.mirrored)
        assertEquals(0f, place.from.yaw)
        assertNull(atlas.place(PrivacyAnimalFace(0f, 0f, 0f, 100f)))
    }

    private fun landmarks(yawDegrees: Double, rollRadians: Double = 0.0): FloatArray {
        // Eyes at x = +/-0.5 and the nose 0.55 in front, rotated by yaw then rolled in the image plane.
        val yaw = yawDegrees * PI / 180
        val points = listOf(-.5 to 0.0, .5 to 0.0, 0.0 to .55, -.35 to .3, .35 to .3)
        val heights = listOf(0.0, 0.0, .4, .8, .8)
        return points.zip(heights).flatMap { (point, y) ->
            val x = point.first * cos(yaw) + point.second * sin(yaw)
            listOf(100 * (x * cos(rollRadians) - y * sin(rollRadians)), 100 * (x * sin(rollRadians) + y * cos(rollRadians)))
        }.map(Double::toFloat).toFloatArray()
    }

    @Test
    fun poseRecoversYawSignAndRoll() {
        for (yaw in listOf(-70.0, -30.0, 0.0, 20.0, 60.0)) {
            val (estimated, roll) = checkNotNull(PrivacyAnimalPose.estimate(landmarks(yaw, .2)))
            assertEquals(yaw.toFloat(), estimated, .5f)
            assertEquals(.2f, roll, 1e-3f)
        }
        // Swapped eye order still measures along the image-right eye line.
        val swapped = landmarks(40.0).let { floatArrayOf(it[2], it[3], it[0], it[1], *it.copyOfRange(4, 10)) }
        assertEquals(40f, checkNotNull(PrivacyAnimalPose.estimate(swapped)).first, .5f)
    }

    @Test
    fun poseRejectsDegenerateOrUpsideDownLandmarks() {
        assertNull(PrivacyAnimalPose.estimate(FloatArray(10)))
        assertNull(PrivacyAnimalPose.estimate(landmarks(0.0, 1.3)))
        assertNull(PrivacyAnimalPose.estimate(landmarks(0.0).also { it[4] = Float.NaN }))
        assertNotNull(PrivacyAnimalPose.estimate(landmarks(0.0, -.9)))
    }

    @Test
    fun smootherKeepsTrackPoseAndForgetsEndedTracks() {
        val smoother = PrivacyAnimalPose.Smoother()
        assertEquals(40f to .2f, smoother.update(listOf(1 to (40f to .2f))).single())
        assertEquals(20f, smoother.update(listOf(1 to (0f to .2f))).single().first, 1e-6f)
        assertEquals(20f, smoother.update(listOf(1 to null)).single().first, 1e-6f)
        smoother.update(listOf(2 to null))
        assertEquals(0f to 0f, smoother.update(listOf(1 to null)).single())
        assertEquals(-10f to 0f, smoother.update(listOf(PrivacySegmentation.NO_TRACK to (-10f to 0f))).single())
    }
}
