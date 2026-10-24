package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.framework.innolive.feature.live.AIProcessingPreference
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Upright-frame face box with smoothed head yaw (degrees, positive turns the nose toward image right) and roll (radians). */
internal data class PrivacyAnimalFace(val left: Float, val top: Float, val right: Float, val bottom: Float,
                                      val yaw: Float = 0f, val roll: Float = 0f)

internal class PrivacyAnimalOverlay(val image: Bitmap, val atlas: PrivacyAnimalAtlas, val faces: List<PrivacyAnimalFace>)

/**
 * Blender-rendered yaw views of one animal. Every cell is the same fixed orthographic camera frame, so a view change
 * only moves the face rectangle; the model scale stays constant. Version 1 front-only sprites load as a single view.
 */
internal class PrivacyAnimalAtlas(val width: Int, val height: Int, val views: List<View>, val mirrorNegativeYaw: Boolean) {
    /** Atlas pixels. */
    data class Cell(val x: Int, val y: Int, val width: Int, val height: Int)
    /** Normalized to its cell, top-left origin. */
    data class Area(val x: Float, val y: Float, val width: Float, val height: Float) {
        val centerX get() = x + width / 2
        val centerY get() = y + height / 2
    }
    class View(val yaw: Float, val cell: Cell, val face: Area)

    /**
     * The canvas-normalized point ([anchorX], [anchorY]) lands on ([centerX], [centerY]) in frame pixels and the canvas
     * is [canvasWidth] x [canvasHeight] frame pixels, rotated by [roll]. [anchorX] is already mirrored.
     */
    class Placement(val from: View, val to: View, val blend: Float, val mirrored: Boolean,
                    val centerX: Float, val centerY: Float, val canvasWidth: Float, val canvasHeight: Float,
                    val anchorX: Float, val anchorY: Float, val roll: Float)

    init {
        require(views.isNotEmpty() && views.first().yaw == 0f && views.zipWithNext().all { (a, b) -> a.yaw < b.yaw })
        require(views.all { it.cell.width == views.first().cell.width && it.cell.height == views.first().cell.height })
    }

    fun place(face: PrivacyAnimalFace): Placement? {
        val boxWidth = face.right - face.left; val boxHeight = face.bottom - face.top
        if (!(boxWidth > 0f && boxHeight > 0f) || !face.yaw.isFinite() || !face.roll.isFinite()) return null
        val mirrored = mirrorNegativeYaw && face.yaw < 0f
        val yaw = (if (mirrorNegativeYaw) abs(face.yaw) else face.yaw).coerceIn(0f, views.last().yaw)
        val lower = views.indexOfLast { it.yaw <= yaw }
        val upper = (lower + 1).coerceAtMost(views.lastIndex)
        val t = if (upper == lower) 0f else (yaw - views[lower].yaw) / (views[upper].yaw - views[lower].yaw)
        // One crisp view almost everywhere; a wide cross-fade would ghost both views' eyes at the midpoint.
        val blend = smoothstep(.45f, .55f, t)
        val a = views[lower].face; val b = views[upper].face
        fun mix(x: Float, y: Float) = x + (y - x) * blend
        val cell = views[lower].cell
        // Uniform scale never squeezes the animal and still covers the whole tracked face box.
        val scale = max(boxWidth / (mix(a.width, b.width) * cell.width),
            boxHeight / (mix(a.height, b.height) * cell.height))
        val anchorX = mix(a.centerX, b.centerX)
        return Placement(views[lower], views[upper], blend, mirrored, (face.left + face.right) / 2,
            (face.top + face.bottom) / 2, cell.width * scale, cell.height * scale,
            if (mirrored) 1f - anchorX else anchorX, mix(a.centerY, b.centerY), face.roll)
    }

    companion object {
        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun normalized(values: JSONArray): Area {
            require(values.length() == 4)
            val f = List(4) { values.getDouble(it).toFloat() }
            require(f.all { it.isFinite() && it in 0f..1f } && f[2] > 0 && f[3] > 0 && f[0] + f[2] <= 1 && f[1] + f[3] <= 1)
            return Area(f[0], f[1], f[2], f[3])
        }

        fun parse(json: JSONObject, imageWidth: Int, imageHeight: Int): PrivacyAnimalAtlas {
            require(json.getInt("width") == imageWidth && json.getInt("height") == imageHeight)
            val array = json.optJSONArray("views")
                ?: return PrivacyAnimalAtlas(imageWidth, imageHeight,
                    listOf(View(0f, Cell(0, 0, imageWidth, imageHeight), normalized(json.getJSONArray("faceRect")))), false)
            val views = List(array.length()) { index ->
                val view = array.getJSONObject(index)
                val c = view.getJSONArray("cell").let { cell -> require(cell.length() == 4); List(4) { cell.getInt(it) } }
                require(c[0] >= 0 && c[1] >= 0 && c[2] > 0 && c[3] > 0 && c[0] + c[2] <= imageWidth && c[1] + c[3] <= imageHeight)
                View(view.getDouble("yaw").toFloat(), Cell(c[0], c[1], c[2], c[3]),
                    normalized(view.getJSONArray("faceRect")))
            }
            return PrivacyAnimalAtlas(imageWidth, imageHeight, views, json.optBoolean("mirrorNegativeYaw", false))
        }
    }
}

/** Head pose from YuNet's five landmarks; the animal only needs a stable yaw view and in-plane roll. */
internal object PrivacyAnimalPose {
    /** Nose tip depth in front of the eye line, in interocular distances, for an average adult face. */
    private const val NOSE_DEPTH = .55f
    private const val MAX_ROLL = PI.toFloat() / 3
    private const val SMOOTHING = .5f

    /**
     * [landmarks] are x/y pairs in YuNet order: right eye, left eye, nose, right and left mouth corners. The nose shifts
     * along the eye line by depth * sin(yaw) while the eye distance shrinks by cos(yaw), so their ratio is depth * tan(yaw).
     */
    fun estimate(landmarks: FloatArray): Pair<Float, Float>? {
        require(landmarks.size == 10)
        if (!landmarks.all(Float::isFinite)) return null
        var axisX = landmarks[2] - landmarks[0]; var axisY = landmarks[3] - landmarks[1]
        // Measure along the eye line pointing toward image right, whichever eye YuNet listed first.
        if (axisX < 0f) { axisX = -axisX; axisY = -axisY }
        val distance = hypot(axisX, axisY)
        if (distance < 1e-3f) return null
        val roll = atan2(axisY, axisX)
        if (abs(roll) > MAX_ROLL) return null
        val noseX = landmarks[4] - (landmarks[0] + landmarks[2]) / 2
        val noseY = landmarks[5] - (landmarks[1] + landmarks[3]) / 2
        val along = (noseX * axisX + noseY * axisY) / distance
        return Math.toDegrees(atan2(along / distance, NOSE_DEPTH).toDouble()).toFloat() to roll
    }

    /** Per-track exponential smoothing; a track without a new estimate keeps its last pose. */
    class Smoother {
        private val poses = HashMap<Int, Pair<Float, Float>>()

        fun update(tracks: List<Pair<Int, Pair<Float, Float>?>>): List<Pair<Float, Float>> {
            val seen = HashSet<Int>()
            val result = tracks.map { (track, estimate) ->
                if (track == PrivacySegmentation.NO_TRACK) return@map estimate ?: (0f to 0f)
                seen += track
                val previous = poses[track]
                val next = when {
                    estimate == null -> previous ?: (0f to 0f)
                    previous == null -> estimate
                    else -> previous.first + (estimate.first - previous.first) * SMOOTHING to
                        previous.second + (estimate.second - previous.second) * SMOOTHING
                }
                poses[track] = next
                next
            }
            poses.keys.retainAll(seen)
            return result
        }

        fun reset() = poses.clear()
    }
}

/** Worker-owned sprite compositor; input must already have privacy blur applied. */
internal class PrivacyAnimalSprite(private val context: Context) {
    private val preferences = context.getSharedPreferences("privacy_animal", Context.MODE_PRIVATE)
    private val unavailable = mutableSetOf<String>()
    private val cache = mutableMapOf<String, Pair<Bitmap, PrivacyAnimalAtlas>>()
    private val smoother = PrivacyAnimalPose.Smoother()
    private var detector: PrivacyYuNetModel? = null
    private var poseUnavailable = false
    private val poseScratch by lazy {
        Bitmap.createBitmap(POSE_INPUT, POSE_INPUT, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }
    private val poseTransform = Matrix()
    private val posePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun asset(): Pair<Bitmap, PrivacyAnimalAtlas>? {
        if (!AIProcessingPreference(context).onDevice) return null
        val animal = preferences.getString("selected", "blur")
        if (animal !in listOf("rabbit", "owl")) return null
        if (animal in unavailable) return null
        return cache[animal] ?: runCatching {
            unavailable.add(checkNotNull(animal))
            val json = context.assets.open("$animal.json").bufferedReader().use { JSONObject(it.readText()) }
            val image = context.assets.open("$animal.png").use { checkNotNull(BitmapFactory.decodeStream(it)) }
            try { image to PrivacyAnimalAtlas.parse(json, image.width, image.height) }
            catch (error: Throwable) { image.recycle(); throw error }
        }.getOrNull()?.also { unavailable.remove(animal); cache[checkNotNull(animal)] = it } ?: return null
    }

    /**
     * Upright-frame boxes of protected faces with a smoothed pose. [crop] returns a [POSE_INPUT]-square, black-padded
     * copy of the given frame square; a face without new landmarks keeps its track's previous pose.
     */
    fun faces(faces: List<PrivacySegmentation.Detection>, layout: PrivacySegmentation.Letterbox,
              crop: ((RectF) -> Bitmap)?): List<PrivacyAnimalFace> {
        val boxes = faces.map {
            val b = it.box
            RectF((b.left - layout.left) * layout.sourceWidth / layout.resizedWidth,
                (b.top - layout.top) * layout.sourceHeight / layout.resizedHeight,
                (b.right - layout.left) * layout.sourceWidth / layout.resizedWidth,
                (b.bottom - layout.top) * layout.sourceHeight / layout.resizedHeight)
        }
        // Larger faces get the per-frame landmark budget first.
        val estimated = boxes.indices.filter { !faces[it].held && !boxes[it].isEmpty }
            .sortedByDescending { boxes[it].width() * boxes[it].height() }.take(MAX_POSES_PER_FRAME).toSet()
        val poses = smoother.update(faces.indices.map { index ->
            faces[index].trackId to if (index in estimated && crop != null) estimate(boxes[index], crop) else null
        })
        return boxes.mapIndexed { index, box ->
            PrivacyAnimalFace(box.left, box.top, box.right, box.bottom, poses[index].first, poses[index].second)
        }
    }

    private fun estimate(box: RectF, crop: (RectF) -> Bitmap): Pair<Float, Float>? {
        if (poseUnavailable) return null
        val side = max(box.width(), box.height()) * POSE_MARGIN
        val square = RectF(box.centerX() - side / 2, box.centerY() - side / 2,
            box.centerX() + side / 2, box.centerY() + side / 2)
        return try {
            val model = detector ?: PrivacyYuNetModel(context.applicationContext).also { detector = it }
            val face = try { model.oneFace(crop(square), enrollment = false) }
                catch (_: AmbiguousFaceSampleException) { null } ?: return null
            PrivacyAnimalPose.estimate(FloatArray(10) { face.landmarks[it / 2].let { p -> if (it % 2 == 0) p.x else p.y } })
        } catch (error: Exception) {
            // The animal keeps the front view; the privacy blur underneath is unchanged.
            poseUnavailable = true
            Log.w("PrivacyAnimal", "pose_unavailable type=${error.javaClass.simpleName}")
            null
        }
    }

    /** CPU pose input from an upright frame; the returned bitmap is reused by the next call. */
    fun crop(upright: Bitmap, square: RectF): Bitmap {
        val canvas = Canvas(poseScratch)
        canvas.drawColor(Color.BLACK)
        poseTransform.setRectToRect(square, RectF(0f, 0f, POSE_INPUT.toFloat(), POSE_INPUT.toFloat()), Matrix.ScaleToFit.FILL)
        canvas.drawBitmap(upright, poseTransform, posePaint)
        return poseScratch
    }

    fun composite(protected: Bitmap, faces: List<PrivacyAnimalFace>): Bitmap {
        if (faces.isEmpty()) return protected
        val (image, atlas) = asset() ?: return protected
        val output = protected.copy(Bitmap.Config.ARGB_8888, true) ?: return protected
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        faces.forEach { face ->
            val place = atlas.place(face) ?: return@forEach
            // The CPU fallback draws the nearest view instead of the GPU cross-fade.
            val view = if (place.blend < .5f) place.from else place.to
            val left = place.centerX - place.anchorX * place.canvasWidth
            val top = place.centerY - place.anchorY * place.canvasHeight
            val target = RectF(left, top, left + place.canvasWidth, top + place.canvasHeight)
            val save = canvas.save()
            canvas.rotate(Math.toDegrees(place.roll.toDouble()).toFloat(), place.centerX, place.centerY)
            if (place.mirrored) canvas.scale(-1f, 1f, target.centerX(), target.centerY())
            val cell = view.cell
            canvas.drawBitmap(image, Rect(cell.x, cell.y, cell.x + cell.width, cell.y + cell.height), target, paint)
            canvas.restoreToCount(save)
        }
        return output
    }

    fun reset() = smoother.reset()

    fun close() {
        cache.values.forEach { it.first.recycle() }; cache.clear()
        detector?.close(); detector = null
    }

    companion object {
        const val POSE_INPUT = 160
        private const val POSE_MARGIN = 1.4f
        private const val MAX_POSES_PER_FRAME = 3
    }
}
