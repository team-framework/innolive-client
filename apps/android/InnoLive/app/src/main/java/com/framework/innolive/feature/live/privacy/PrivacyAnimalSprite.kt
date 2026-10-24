package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One protected face whose YuNet landmarks (x/y pairs, upright frame pixels) passed the face-swap checks. */
internal class PrivacyAnimalFace(val landmarks: FloatArray, val yaw: Float) {
    init { require(landmarks.size == 10) }
}

internal class PrivacyAnimalOverlay(val image: Bitmap, val atlas: PrivacyAnimalAtlas, val faces: List<PrivacyAnimalFace>)

/**
 * Blender-rendered yaw views of one animal. Every cell is the same fixed orthographic camera frame. Each view carries
 * the ArcFace 5-point template projected onto the animal head, so a face aligns like the server's InSwapper crop.
 */
internal class PrivacyAnimalAtlas(val width: Int, val height: Int, val views: List<View>, val mirrorNegativeYaw: Boolean) {
    /** Atlas pixels. */
    data class Cell(val x: Int, val y: Int, val width: Int, val height: Int)
    /** [landmarks] are x/y pairs normalized to the cell, top-left origin, in YuNet order. */
    class View(val yaw: Float, val cell: Cell, val landmarks: FloatArray)

    /**
     * Similarity transform from mirrored-canvas pixels to frame pixels: frame = [a -b; b a] * canvas + t.
     * The two neighbouring yaw views share the canvas and cross-fade by [blend].
     */
    class Placement(val from: View, val to: View, val blend: Float, val mirrored: Boolean,
                    val a: Float, val b: Float, val tx: Float, val ty: Float)

    init {
        require(views.isNotEmpty() && views.first().yaw == 0f && views.zipWithNext().all { (a, b) -> a.yaw < b.yaw })
        require(views.all { it.cell.width == views.first().cell.width && it.cell.height == views.first().cell.height })
        require(views.all { it.landmarks.size == 10 })
    }

    fun place(face: PrivacyAnimalFace): Placement? {
        if (!face.yaw.isFinite() || !face.landmarks.all(Float::isFinite)) return null
        val mirrored = mirrorNegativeYaw && face.yaw < 0f
        val yaw = (if (mirrorNegativeYaw) abs(face.yaw) else face.yaw).coerceIn(0f, views.last().yaw)
        val lower = views.indexOfLast { it.yaw <= yaw }
        val upper = (lower + 1).coerceAtMost(views.lastIndex)
        val t = if (upper == lower) 0f else (yaw - views[lower].yaw) / (views[upper].yaw - views[lower].yaw)
        // One crisp view almost everywhere; a wide cross-fade would ghost both views' eyes at the midpoint.
        val blend = smoothstep(.45f, .55f, t)
        val cell = views[lower].cell
        val reference = FloatArray(10) { index ->
            val value = views[lower].landmarks[index] + (views[upper].landmarks[index] - views[lower].landmarks[index]) * blend
            if (index % 2 == 0) value * cell.width else value * cell.height
        }
        // A mirrored cell swaps which eye and mouth corner lies on the image-left side.
        val source = if (!mirrored) reference else FloatArray(10) { index ->
            val point = MIRRORED_ORDER[index / 2] * 2 + index % 2
            if (index % 2 == 0) cell.width - reference[point] else reference[point]
        }
        val (a, b, tx, ty) = similarity(source, face.landmarks) ?: return null
        return Placement(views[lower], views[upper], blend, mirrored, a, b, tx, ty)
    }

    companion object {
        private val MIRRORED_ORDER = intArrayOf(1, 0, 2, 4, 3)

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Least-squares similarity (rotation, uniform scale, translation) mapping [source] onto [target] points. */
        internal fun similarity(source: FloatArray, target: FloatArray): FloatArray? {
            val count = source.size / 2
            var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
            for (i in 0 until count) { sx += source[2*i]; sy += source[2*i+1]; dx += target[2*i]; dy += target[2*i+1] }
            sx /= count; sy /= count; dx /= count; dy /= count
            var norm = 0.0; var dot = 0.0; var cross = 0.0
            for (i in 0 until count) {
                val px = source[2*i] - sx; val py = source[2*i+1] - sy
                val qx = target[2*i] - dx; val qy = target[2*i+1] - dy
                norm += px*px + py*py; dot += px*qx + py*qy; cross += px*qy - py*qx
            }
            if (norm < 1e-6) return null
            val a = dot / norm; val b = cross / norm
            if (hypot(a, b) < 1e-6 || !a.isFinite() || !b.isFinite()) return null
            return floatArrayOf(a.toFloat(), b.toFloat(), (dx - (a*sx - b*sy)).toFloat(), (dy - (b*sx + a*sy)).toFloat())
        }

        private fun floats(values: JSONArray, size: Int): FloatArray {
            require(values.length() == size)
            return FloatArray(size) { values.getDouble(it).toFloat().also { v -> require(v.isFinite() && v in 0f..1f) } }
        }

        fun parse(json: JSONObject, imageWidth: Int, imageHeight: Int): PrivacyAnimalAtlas {
            require(json.getInt("width") == imageWidth && json.getInt("height") == imageHeight && json.getInt("version") >= 2)
            val array = json.getJSONArray("views")
            val views = List(array.length()) { index ->
                val view = array.getJSONObject(index)
                val c = view.getJSONArray("cell").let { cell -> require(cell.length() == 4); List(4) { cell.getInt(it) } }
                require(c[0] >= 0 && c[1] >= 0 && c[2] > 0 && c[3] > 0 && c[0] + c[2] <= imageWidth && c[1] + c[3] <= imageHeight)
                val points = view.getJSONArray("landmarks").let { list ->
                    require(list.length() == 5)
                    FloatArray(10) { list.getJSONArray(it / 2).let { p -> require(p.length() == 2); floats(p, 2)[it % 2] } }
                }
                View(view.getDouble("yaw").toFloat(), Cell(c[0], c[1], c[2], c[3]), points)
            }
            return PrivacyAnimalAtlas(imageWidth, imageHeight, views, json.optBoolean("mirrorNegativeYaw", false))
        }
    }
}

/** Head yaw from YuNet's five landmarks; only used to pick the animal's yaw view. */
internal object PrivacyAnimalPose {
    /** Nose tip depth in front of the eye line, in interocular distances, for an average adult face. */
    private const val NOSE_DEPTH = .55f

    /**
     * [landmarks] are x/y pairs in YuNet order: right eye, left eye, nose, right and left mouth corners. The nose shifts
     * along the eye line by depth * sin(yaw) while the eye distance shrinks by cos(yaw), so their ratio is depth * tan(yaw).
     * Positive yaw turns the nose toward image right.
     */
    fun yaw(landmarks: FloatArray): Float? {
        require(landmarks.size == 10)
        if (!landmarks.all(Float::isFinite)) return null
        var axisX = landmarks[2] - landmarks[0]; var axisY = landmarks[3] - landmarks[1]
        // Measure along the eye line pointing toward image right, whichever eye YuNet listed first.
        if (axisX < 0f) { axisX = -axisX; axisY = -axisY }
        val distance = hypot(axisX, axisY)
        if (distance < 1e-3f) return null
        val noseX = landmarks[4] - (landmarks[0] + landmarks[2]) / 2
        val noseY = landmarks[5] - (landmarks[1] + landmarks[3]) / 2
        val along = (noseX * axisX + noseY * axisY) / distance
        return Math.toDegrees(atan2(along / distance, NOSE_DEPTH).toDouble()).toFloat()
    }
}

/** Face-swap target rules (innolive-ai `InSwapperRenderer.swap`) applied to on-device animal overlays. */
internal object PrivacyAnimalTargets {
    /** YOLO box padding on each side, relative to its longer side. */
    const val ROI_PADDING = .3f
    const val MIN_IOU = .25f
    const val MIN_SCORE = .65f
    /** `face_too_small`: a bbox whose shorter side is under 16 px stays blurred. */
    const val MIN_FACE = 16f

    /** Boxes are (left, top, right, bottom) frame pixels. */
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** Padded integer ROI clipped to the frame, or null when the face is too small or outside the frame. */
    fun roi(box: Box, width: Int, height: Int): IntArray? {
        if (min(box.width, box.height) < MIN_FACE) return null
        val pad = max(box.width, box.height) * ROI_PADDING
        val roi = intArrayOf(max(0, (box.left - pad).toInt()), max(0, (box.top - pad).toInt()),
            min(width, (box.right + pad).toInt()), min(height, (box.bottom + pad).toInt()))
        return roi.takeIf { it[2] > it[0] && it[3] > it[1] }
    }

    /** Landmarks of the candidate overlapping the tracked box by more than [MIN_IOU], or null. */
    fun match(box: Box, candidates: List<Pair<Box, FloatArray>>): FloatArray? {
        var best: FloatArray? = null; var bestIou = MIN_IOU
        for ((candidate, landmarks) in candidates) {
            val w = max(0f, min(box.right, candidate.right) - max(box.left, candidate.left))
            val h = max(0f, min(box.bottom, candidate.bottom) - max(box.top, candidate.top))
            val intersection = w * h
            val iou = intersection / max(box.width * box.height + candidate.width * candidate.height - intersection, 1f)
            if (iou > bestIou) { best = landmarks; bestIou = iou }
        }
        return best
    }
}

/**
 * Per-track landmarks refreshed every [refreshFrames] processed frames, counted like the server's 30-frame metadata
 * cache. Between refreshes the landmarks follow the tracked box; a failed refresh keeps the blur until the next one.
 */
internal class PrivacyAnimalLandmarkCache(private val refreshFrames: Int = REFRESH_FRAMES) {
    private class Entry(val refreshedAt: Long, val relative: FloatArray?)
    private val entries = HashMap<Int, Entry>()
    private var frame = 0L

    /** Starts a frame; tracks no longer present, including held ones, drop their landmarks. */
    fun beginFrame(liveTracks: Set<Int>) { frame++; entries.keys.retainAll(liveTracks) }

    /** Frame-pixel landmarks for [track] in the box (left, top, right, bottom), or null to keep the blur. */
    fun landmarks(track: Int, left: Float, top: Float, right: Float, bottom: Float, estimate: () -> FloatArray?): FloatArray? {
        val width = right - left; val height = bottom - top
        val cached = entries[track]
        val relative = if (cached != null && frame - cached.refreshedAt < refreshFrames) cached.relative
            else estimate()?.let { points -> FloatArray(10) { i -> if (i % 2 == 0) (points[i] - left) / width else (points[i] - top) / height } }
                .also { entries[track] = Entry(frame, it) }
        return relative?.let { r -> FloatArray(10) { i -> if (i % 2 == 0) left + r[i] * width else top + r[i] * height } }
    }

    fun reset() { entries.clear(); frame = 0L }

    companion object { const val REFRESH_FRAMES = 3 }
}

/**
 * Worker-owned compositor for one session's animal, fixed when the session was created. Like face swap, an animal
 * replaces a face only inside its protected mask; unmatched, untracked, held or tiny faces keep the blur.
 */
internal class PrivacyAnimalSprite(private val context: Context, private val animal: String?) {
    private var unavailable = false
    private var cached: Pair<Bitmap, PrivacyAnimalAtlas>? = null
    private var detector: PrivacyYuNetModel? = null
    private var detectorUnavailable = false
    private val landmarkCache = PrivacyAnimalLandmarkCache()
    private val poseScratch by lazy {
        Bitmap.createBitmap(POSE_INPUT, POSE_INPUT, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }
    private val poseTransform = Matrix()
    private val posePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** This frame's landmark cost for [PrivacyAnimalStats]: crop/readback and YuNet, summed over faces. */
    var lastCropNs = 0L
        private set
    var lastLandmarkNs = 0L
        private set

    val mode: String get() = if (asset() == null) "blur" else checkNotNull(animal)

    fun beginFrame() { lastCropNs = 0L; lastLandmarkNs = 0L }

    fun asset(): Pair<Bitmap, PrivacyAnimalAtlas>? {
        if (animal !in ANIMALS || unavailable) return null
        cached?.let { return it }
        return runCatching {
            val json = context.assets.open("$animal.json").bufferedReader().use { JSONObject(it.readText()) }
            val image = context.assets.open("$animal.png").use { checkNotNull(BitmapFactory.decodeStream(it)) }
            try { image to PrivacyAnimalAtlas.parse(json, image.width, image.height) }
            catch (error: Throwable) { image.recycle(); throw error }
        }.onFailure { unavailable = true }.getOrNull()?.also { cached = it }
    }

    /**
     * Faces that get an animal this frame. [crop] returns a [POSE_INPUT]-square, black-padded copy of the given frame
     * square; the bitmap may be reused by the next call.
     */
    fun faces(faces: List<PrivacySegmentation.Detection>, layout: PrivacySegmentation.Letterbox,
              crop: ((RectF) -> Bitmap)?): List<PrivacyAnimalFace> {
        if (crop == null || detectorUnavailable) return emptyList()
        landmarkCache.beginFrame(faces.map { it.trackId }.toSet())
        return faces.mapNotNull { detection ->
            // Untracked faces and held masks keep the blur, as face swap does.
            if (detection.trackId == PrivacySegmentation.NO_TRACK || detection.held) return@mapNotNull null
            val b = detection.box
            val box = PrivacyAnimalTargets.Box((b.left - layout.left) * layout.sourceWidth / layout.resizedWidth,
                (b.top - layout.top) * layout.sourceHeight / layout.resizedHeight,
                (b.right - layout.left) * layout.sourceWidth / layout.resizedWidth,
                (b.bottom - layout.top) * layout.sourceHeight / layout.resizedHeight)
            val roi = PrivacyAnimalTargets.roi(box, layout.sourceWidth, layout.sourceHeight) ?: return@mapNotNull null
            val landmarks = landmarkCache.landmarks(detection.trackId, box.left, box.top, box.right, box.bottom) {
                landmarks(box, roi, crop) } ?: return@mapNotNull null
            PrivacyAnimalFace(landmarks, PrivacyAnimalPose.yaw(landmarks) ?: return@mapNotNull null)
        }
    }

    private fun landmarks(box: PrivacyAnimalTargets.Box, roi: IntArray, crop: (RectF) -> Bitmap): FloatArray? = try {
        val model = detector ?: PrivacyYuNetModel(context.applicationContext).also { detector = it }
        // The ROI sits top-left in a square input scaled to POSE_INPUT; the rest is frame pixels or black padding.
        val left = roi[0].toFloat(); val top = roi[1].toFloat()
        val side = max(roi[2] - roi[0], roi[3] - roi[1]).toFloat()
        val scale = side / POSE_INPUT
        val started = System.nanoTime()
        val input = crop(RectF(left, top, left + side, top + side))
        val cropped = System.nanoTime()
        try {
            PrivacyAnimalTargets.match(box, model.faces(input).filter { it.score >= PrivacyAnimalTargets.MIN_SCORE }.map { face ->
                PrivacyAnimalTargets.Box(left + face.box.left * scale, top + face.box.top * scale,
                    left + face.box.right * scale, top + face.box.bottom * scale) to
                    FloatArray(10) { index -> face.landmarks[index / 2].let { p ->
                        if (index % 2 == 0) left + p.x * scale else top + p.y * scale } }
            })
        } finally { lastCropNs += cropped - started; lastLandmarkNs += System.nanoTime() - cropped }
    } catch (error: Exception) {
        // `swapper_unavailable`: every face keeps the blur; frames are never delivered without protection.
        detectorUnavailable = true
        Log.w("PrivacyAnimal", "landmarks_unavailable type=${error.javaClass.simpleName}")
        null
    }

    /** CPU pose input from an upright frame; the returned bitmap is reused by the next call. */
    fun crop(upright: Bitmap, square: RectF): Bitmap {
        val canvas = Canvas(poseScratch)
        canvas.drawColor(Color.BLACK)
        poseTransform.setRectToRect(square, RectF(0f, 0f, POSE_INPUT.toFloat(), POSE_INPUT.toFloat()), Matrix.ScaleToFit.FILL)
        canvas.drawBitmap(upright, poseTransform, posePaint)
        return poseScratch
    }

    /** CPU fallback: animals over the already blurred frame, clipped to the protected [mask] like the GPU pass. */
    fun composite(protected: Bitmap, faces: List<PrivacyAnimalFace>, mask: ByteArray,
                  layout: PrivacySegmentation.Letterbox, edgeCells: Int): Bitmap {
        if (faces.isEmpty()) return protected
        val (image, atlas) = asset() ?: return protected
        val output = protected.copy(Bitmap.Config.ARGB_8888, true) ?: return protected
        val canvas = Canvas(output)
        val layer = canvas.saveLayer(null, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        faces.forEach { face ->
            val place = atlas.place(face) ?: return@forEach
            // The CPU fallback draws the nearest view instead of the GPU cross-fade.
            val view = if (place.blend < .5f) place.from else place.to
            val cell = view.cell
            val transform = Matrix().apply {
                if (place.mirrored) { setScale(-1f, 1f); postTranslate(cell.width.toFloat(), 0f) }
                postConcat(Matrix().apply { setValues(floatArrayOf(place.a, -place.b, place.tx, place.b, place.a, place.ty, 0f, 0f, 1f)) })
            }
            val save = canvas.save()
            canvas.concat(transform)
            canvas.drawBitmap(image, Rect(cell.x, cell.y, cell.x + cell.width, cell.y + cell.height),
                RectF(0f, 0f, cell.width.toFloat(), cell.height.toFloat()), paint)
            canvas.restoreToCount(save)
        }
        val expanded = PrivacyMaskRenderer.expandedMask(mask, edgeCells)
        val size = PrivacySegmentation.MASK_SIZE
        val alpha = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        alpha.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(expanded))
        val scaleX = 640f / size * layout.sourceWidth / layout.resizedWidth
        val scaleY = 640f / size * layout.sourceHeight / layout.resizedHeight
        canvas.drawBitmap(alpha, Matrix().apply {
            setScale(scaleX, scaleY)
            postTranslate(-layout.left * layout.sourceWidth.toFloat() / layout.resizedWidth,
                -layout.top * layout.sourceHeight.toFloat() / layout.resizedHeight)
        }, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        canvas.restoreToCount(layer)
        alpha.recycle()
        return output
    }

    fun reset() = landmarkCache.reset()

    fun close() {
        cached?.first?.recycle(); cached = null
        detector?.close(); detector = null
    }

    companion object {
        const val POSE_INPUT = 160
        val ANIMALS = setOf("rabbit", "owl")
    }
}

/** Debug-only 5 s summary of what an animal overlay adds per frame, logged per mode for blur/animal A/B runs. */
internal class PrivacyAnimalStats {
    private var mode = ""
    private var startedNs = System.nanoTime()
    private var faces = 0
    private val frame = ArrayList<Double>(); private val crop = ArrayList<Double>()
    private val landmarks = ArrayList<Double>(); private val drawCpu = ArrayList<Double>()
    private val drawGpu = ArrayList<Double>()

    fun record(mode: String, frameMs: Double, faceCount: Int, cropNs: Long, landmarkNs: Long, drawCpuNs: Long, drawGpuNs: Long?) {
        val now = System.nanoTime()
        if (mode != this.mode) { clear(); this.mode = mode; startedNs = now }
        frame += frameMs; faces += faceCount
        crop += cropNs / 1e6; landmarks += landmarkNs / 1e6; drawCpu += drawCpuNs / 1e6
        drawGpuNs?.let { drawGpu += it / 1e6 }
        if (now - startedNs < 5_000_000_000L) return
        fun p(values: List<Double>, q: Double) = values.sorted().let { if (it.isEmpty()) -1.0 else it[((it.size - 1) * q).toInt()] }
        fun f(values: List<Double>) = "%.2f/%.2f".format(p(values, .5), p(values, .95))
        Log.i("PrivacyAnimal", "mode=$mode frames=${frame.size} faces_avg=${"%.2f".format(faces.toDouble() / frame.size)} " +
            "frame_ms=${f(frame)} crop_ms=${f(crop)} yunet_ms=${f(landmarks)} draw_cpu_ms=${f(drawCpu)} " +
            "draw_gpu_ms=${f(drawGpu)} gpu_samples=${drawGpu.size}")
        clear(); startedNs = now
    }

    private fun clear() { frame.clear(); crop.clear(); landmarks.clear(); drawCpu.clear(); drawGpu.clear(); faces = 0 }
}
