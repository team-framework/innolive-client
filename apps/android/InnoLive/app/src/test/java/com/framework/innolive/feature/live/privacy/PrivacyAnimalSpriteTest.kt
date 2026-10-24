package com.framework.innolive.feature.live.privacy

import com.framework.innolive.feature.live.FaceAnonymizationMethod
import com.framework.innolive.feature.live.sessionAnimal
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** ArcFace-like 5 points: eyes 100 px apart at (cx, cy), rotated by [roll]. */
    private fun face(cx: Float, cy: Float, scale: Float = 100f, roll: Double = 0.0): FloatArray {
        val template = floatArrayOf(-.5f, 0f, .5f, 0f, 0f, .57f, -.41f, 1.16f, .41f, 1.16f)
        return FloatArray(10) { i ->
            val x = template[i - i % 2]; val y = template[i - i % 2 + 1]
            if (i % 2 == 0) cx + scale * (x * cos(roll) - y * sin(roll)).toFloat()
            else cy + scale * (x * sin(roll) + y * cos(roll)).toFloat()
        }
    }

    private fun apply(place: PrivacyAnimalAtlas.Placement, x: Float, y: Float) =
        floatArrayOf(place.a * x - place.b * y + place.tx, place.b * x + place.a * y + place.ty)

    @Test
    fun bundledAtlasesCarryYawViewsWithTemplateLandmarks() {
        for (name in listOf("rabbit", "owl")) {
            val atlas = atlas(name)
            assertTrue(atlas.mirrorNegativeYaw)
            assertEquals((0..90 step 10).map(Int::toFloat), atlas.views.map { it.yaw })
            val front = atlas.views.first().landmarks
            // Front view: eyes level and symmetric, nose between them, mouth below.
            assertEquals(front[1], front[3], 1e-3f)
            assertEquals(.5f, (front[0] + front[2]) / 2, 1e-2f)
            assertTrue(front[5] > front[1] && front[7] > front[5])
        }
    }

    @Test
    fun similarityRecoversRotationScaleAndTranslation() {
        val source = floatArrayOf(0f, 0f, 10f, 0f, 5f, 6f, 2f, 11f, 8f, 11f)
        val angle = .3; val scale = 2.5f
        val target = FloatArray(10) { i ->
            val x = source[i - i % 2]; val y = source[i - i % 2 + 1]
            if (i % 2 == 0) 40f + scale * (x * cos(angle) - y * sin(angle)).toFloat()
            else -7f + scale * (x * sin(angle) + y * cos(angle)).toFloat()
        }
        val (a, b, tx, ty) = checkNotNull(PrivacyAnimalAtlas.similarity(source, target))
        assertEquals(scale * cos(angle).toFloat(), a, 1e-4f)
        assertEquals(scale * sin(angle).toFloat(), b, 1e-4f)
        assertEquals(40f, tx, 1e-3f); assertEquals(-7f, ty, 1e-3f)
        assertNull(PrivacyAnimalAtlas.similarity(FloatArray(10) { 3f }, target))
    }

    @Test
    fun frontFaceLandmarksLandOnTheAnimalTemplate() {
        val atlas = atlas("rabbit")
        val landmarks = face(400f, 300f, roll = .2)
        val place = checkNotNull(atlas.place(PrivacyAnimalFace(landmarks, 0f)))
        val cell = place.from.cell
        val eye = apply(place, place.from.landmarks[0] * cell.width, place.from.landmarks[1] * cell.height)
        assertEquals(landmarks[0], eye[0], 6f); assertEquals(landmarks[1], eye[1], 6f)
        // Roll follows the face: the similarity rotation equals the eye-line angle.
        assertEquals(.2f, kotlin.math.atan2(place.b, place.a), .03f)
    }

    @Test
    fun negativeYawMirrorsTheCellAndSwapsSides() {
        val atlas = atlas("owl")
        val right = checkNotNull(atlas.place(PrivacyAnimalFace(face(300f, 300f), 50f)))
        val left = checkNotNull(atlas.place(PrivacyAnimalFace(face(300f, 300f), -50f)))
        assertFalse(right.mirrored); assertTrue(left.mirrored)
        assertEquals(right.from.yaw, left.from.yaw)
    }

    @Test
    fun crossFadeOnlyNearTheMidpointBetweenViews() {
        val atlas = atlas("rabbit")
        fun blend(yaw: Float) = checkNotNull(atlas.place(PrivacyAnimalFace(face(200f, 200f), yaw))).blend
        assertEquals(0f, blend(24.4f), 0f)
        assertEquals(.5f, blend(25f), 1e-5f)
        assertEquals(1f, blend(25.6f), 0f)
        assertEquals(90f, checkNotNull(atlas.place(PrivacyAnimalFace(face(200f, 200f), 120f))).from.yaw)
    }

    @Test
    fun yawRecoversSignFromLandmarks() {
        for (yaw in listOf(-70.0, -30.0, 0.0, 20.0, 60.0)) {
            val r = yaw * PI / 180
            val points = listOf(-.5 to 0.0, .5 to 0.0, 0.0 to .55, -.35 to .3, .35 to .3)
            val heights = listOf(0.0, 0.0, .4, .8, .8)
            val landmarks = points.zip(heights).flatMap { (p, y) ->
                listOf(100 * (p.first * cos(r) + p.second * sin(r)), 100 * y) }.map(Double::toFloat).toFloatArray()
            assertEquals(yaw.toFloat(), checkNotNull(PrivacyAnimalPose.yaw(landmarks)), .5f)
        }
        assertNull(PrivacyAnimalPose.yaw(FloatArray(10)))
    }

    @Test
    fun faceSwapTargetRulesForRoiAndOverlap() {
        val box = PrivacyAnimalTargets.Box(100f, 100f, 200f, 220f)
        // 0.3 x longer side (120) = 36 px padding, clipped to the 1280 x 250 frame.
        assertArrayEquals(intArrayOf(64, 64, 236, 250), PrivacyAnimalTargets.roi(box, 1280, 250))
        assertNull(PrivacyAnimalTargets.roi(PrivacyAnimalTargets.Box(0f, 0f, 15f, 40f), 100, 100))
        val near = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
        val far = FloatArray(10)
        assertEquals(near, PrivacyAnimalTargets.match(box, listOf(
            PrivacyAnimalTargets.Box(300f, 100f, 400f, 220f) to far,
            PrivacyAnimalTargets.Box(105f, 110f, 205f, 225f) to near)))
        // IoU must exceed 0.25, as in InSwapperRenderer.swap.
        assertNull(PrivacyAnimalTargets.match(box, listOf(PrivacyAnimalTargets.Box(170f, 100f, 270f, 220f) to near)))
    }

    @Test
    fun landmarkCacheRefreshesEveryThreeFramesAndFollowsTheBox() {
        val cache = PrivacyAnimalLandmarkCache()
        var runs = 0
        val estimate = { runs++; FloatArray(10) { if (it % 2 == 0) 10f else 20f } }
        repeat(7) { frame ->
            cache.beginFrame(setOf(1))
            // The box moves 5 px right each frame; cached landmarks move with it.
            val left = 5f * frame
            val points = checkNotNull(cache.landmarks(1, left, 0f, left + 40f, 40f, estimate))
            if (frame < 3) assertEquals(10f + 5f * frame, points[0], 1e-4f)
        }
        assertEquals(3, runs) // frames 1, 4, 7
        cache.beginFrame(emptySet())
        cache.beginFrame(setOf(1))
        cache.landmarks(1, 0f, 0f, 40f, 40f, estimate)
        assertEquals(4, runs) // an ended track starts over
    }

    @Test
    fun failedRefreshKeepsBlurUntilTheNextRefresh() {
        val cache = PrivacyAnimalLandmarkCache()
        var runs = 0
        val results = mutableListOf<FloatArray?>()
        repeat(4) {
            cache.beginFrame(setOf(7))
            results += cache.landmarks(7, 0f, 0f, 10f, 10f) { runs++; if (runs == 1) null else FloatArray(10) { 1f } }
        }
        assertArrayEquals(arrayOf(null, null, null), results.take(3).toTypedArray())
        assertTrue(results[3] != null)
        assertEquals(2, runs)
    }

    @Test
    fun animalsAreFixedPerSessionOnlyForOnDeviceProcessing() {
        assertEquals("rabbit", sessionAnimal(FaceAnonymizationMethod.RABBIT, onDeviceProcessing = true))
        assertNull(sessionAnimal(FaceAnonymizationMethod.OWL, onDeviceProcessing = false))
        assertNull(sessionAnimal(FaceAnonymizationMethod.FACE_SWAP, onDeviceProcessing = true))
        assertEquals(FaceAnonymizationMethod.OWL, FaceAnonymizationMethod.from("owl"))
        assertNull(FaceAnonymizationMethod.from("cat"))
    }
}
