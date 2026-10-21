package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.framework.innolive.BuildConfig
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private fun registeredFacesForAccount(context: Context, accountScope: String?): () -> List<PrivacyRegisteredFace> {
    val library = accountScope?.let { scope ->
        runCatching { PrivacyFaceLibrary(context, scope) }.getOrNull()
    }
    return { library?.snapshot().orEmpty() }
}

/** Owned by the serial camera analyzer; the worker returns through a single result mailbox. */
internal class PrivacyFaceCoordinator(
    private val service: PrivacyFaceRecognitionService,
    private val optimized: Boolean = true,
    private val revisionSource: () -> Long = { PrivacyFaceLibrary.currentRevision },
    private val registeredFaces: () -> List<PrivacyRegisteredFace>,
) : AutoCloseable {
    constructor(context: Context, accountScope: String? = null, optimized: Boolean = true) : this(
        PrivacyFaceService.get(context), optimized,
        { PrivacyFaceLibrary.currentRevision },
        registeredFacesForAccount(context, accountScope),
    )
    private val tracking = PrivacyFaceTracking()
    private var revision = revisionSource()
    private var generation = 0L
    private var previousLayout: PrivacySegmentation.Letterbox? = null
    private var geometry: Pair<Int, Int>? = null
    private var leased = false
    private var cachedEntries: List<PrivacyRegisteredFace>? = null
    /** Capture times of AdaFace submissions in the last second. */
    private val recentSubmissions = ArrayDeque<Double>()
    /** BoT-SORT IDs that an approved face keeps following while its face is briefly undetected. */
    var liveTrackIds: () -> Set<Int>? = { null }
    private val stats = if (BuildConfig.DEBUG || BuildConfig.PRIVACY_PERF_DIAGNOSTICS) Stats() else null

    /** Debug-only recognition and exemption counts, logged every [Stats.INTERVAL_NS]. */
    private class Stats {
        var frames = 0; var exemptFrames = 0; var submitted = 0; var matched = 0; var unmatched = 0
        var noSample = 0; var revoked = 0; var granted = 0; var tracks = HashSet<Int>()
        private var previous = emptySet<Int>()
        private var startedNs = System.nanoTime()

        fun frame(objects: List<PrivacySegmentation.Detection>, allowed: Set<Int>) {
            frames++
            if (allowed.isNotEmpty()) exemptFrames++
            val ids = allowed.mapTo(HashSet()) { objects[it].trackId }
            granted += (ids - previous).size
            revoked += (previous - ids).size
            previous = ids
            objects.filter { it.classId == 0 && it.trackId != PrivacySegmentation.NO_TRACK }.mapTo(tracks) { it.trackId }
            val now = System.nanoTime()
            if (now - startedNs < INTERVAL_NS) return
            android.util.Log.i("PrivacyFace", "frames=$frames exempt_frames=$exemptFrames granted=$granted " +
                "revoked=$revoked submitted=$submitted matched=$matched unmatched=$unmatched no_sample=$noSample " +
                "face_tracks=${tracks.size}")
            frames = 0; exemptFrames = 0; submitted = 0; matched = 0; unmatched = 0; noSample = 0
            revoked = 0; granted = 0; tracks = HashSet(); startedNs = now
        }

        companion object { const val INTERVAL_NS = 5_000_000_000L }
    }

    fun reset() {
        generation++
        tracking.reset()
        previousLayout = null
    }

    /** Finish the expensive face model validation before protected frames can enter WebRTC. */
    fun prewarmForUplink() {
        refreshRevision()
        if (entries().isEmpty()) { releaseLease(); return }
        if (!leased) { service.retain(); leased = true }
        service.prepare()
        if (!service.awaitPreparation(20_000)) {
            android.util.Log.w("PrivacyFace", "prewarm_unavailable_blur_all=true")
        }
    }

    /** Start at most one independent recognition job. The analyzer never waits for its result. */
    fun beginFrame(upright: Bitmap, timestampNs: Long) =
        beginFrame(upright.width, upright.height, timestampNs) { copyCrop(upright, it) }

    fun beginFrame(width: Int, height: Int, timestampNs: Long, crop: (Rect) -> Bitmap) =
        beginFrame(width,height,timestampNs,crop,null)

    private fun beginFrame(width: Int, height: Int, timestampNs: Long, crop: (Rect) -> Bitmap,
                   readback: ((Rect) -> PrivacyFaceReadback)?) {
        refreshRevision()
        val dimensions = width to height
        if (geometry != dimensions) { reset(); geometry = dimensions }
        if (entries().isEmpty()) { releaseLease(); return }
        if (!leased) { service.retain(); leased = true }
        service.prepare()
        if (!service.canSubmit) return
        val layout = previousLayout ?: return
        val time = timestampNs / 1_000_000_000.0
        // Each AdaFace run competes with the detector for the GPU, so the whole frame gets a fixed budget.
        while (recentSubmissions.isNotEmpty() &&
            (time < recentSubmissions.first() || time - recentSubmissions.first() >= 1.0)) recentSubmissions.removeFirst()
        if (recentSubmissions.size >= MAX_RECOGNITIONS_PER_SECOND) return
        val next = tracking.next(time) { recognitionBounds(width, height, it.box, layout) != null } ?: return
        val submitted = try {
            submit(width, height, timestampNs, next, layout, crop, readback)
        } catch (error: Throwable) { tracking.cancel(next.id); throw error }
        if (submitted) {
            recentSubmissions.addLast(time)
            stats?.let { it.submitted++ }
        } else tracking.cancel(next.id)
    }

    private fun submit(width: Int, height: Int, timestampNs: Long, next: PrivacyFaceTracking.Track,
                       layout: PrivacySegmentation.Letterbox, crop: (Rect) -> Bitmap,
                       readback: ((Rect) -> PrivacyFaceReadback)?): Boolean {
        val bounds = recognitionBounds(width, height, next.box, layout) ?: return false
        if (readback != null) {
            val sample = readback(bounds)
            try {
                return service.submitReadback(sample, bounds, generation, next.id, timestampNs / 1_000_000_000.0)
                    .also { if (!it) sample.close() }
            } catch (error: Throwable) { sample.close(); throw error }
        }
        val image = crop(bounds)
        return service.submitRecognition(image, bounds, generation, next.id, timestampNs / 1_000_000_000.0)
            .also { if (!it) image.recycle() }
    }

    fun currentFrame(upright: Bitmap, objects: List<PrivacySegmentation.Detection>,
                     layout: PrivacySegmentation.Letterbox, timestampNs: Long): Set<Int> =
        currentFrame(upright.width,upright.height,objects,layout,timestampNs) { copyCrop(upright,it) }

    /** Select crops from this frame's BoT-SORT tracks; start recognition after detector GPU work. */
    fun currentFrame(width: Int, height: Int, objects: List<PrivacySegmentation.Detection>,
                     layout: PrivacySegmentation.Letterbox, timestampNs: Long, crop: (Rect) -> Bitmap): Set<Int> =
        currentFrame(width,height,objects,layout,timestampNs,crop,null)

    fun currentFrame(width: Int, height: Int, objects: List<PrivacySegmentation.Detection>,
                     layout: PrivacySegmentation.Letterbox, timestampNs: Long, crop: (Rect) -> Bitmap,
                     readback: ((Rect) -> PrivacyFaceReadback)?): Set<Int> {
        if (geometry != width to height) { reset(); geometry = width to height }
        val allowed = exceptions(width,height,objects,layout,timestampNs)
        beginFrame(width,height,timestampNs,crop,readback)
        return allowed
    }

    private fun releaseLease() { if (leased) { service.release(); leased=false } }
    override fun close() { reset(); releaseLease() }

    fun exceptions(upright: Bitmap, objects: List<PrivacySegmentation.Detection>,
                   layout: PrivacySegmentation.Letterbox, timestampNs: Long): Set<Int> =
        exceptions(upright.width, upright.height, objects, layout, timestampNs)

    fun exceptions(width: Int, height: Int, objects: List<PrivacySegmentation.Detection>,
                   layout: PrivacySegmentation.Letterbox, timestampNs: Long): Set<Int> {
        val time = timestampNs / 1_000_000_000.0
        refreshRevision()
        val faces = objects.withIndex().filter { it.value.classId == 0 }.map { (index, face) ->
            PrivacyFaceTracking.Face(index, face.trackId, face.box, face.held)
        }
        tracking.update(faces, time, liveTrackIds())
        previousLayout = layout
        val entries = entries()
        service.takeResult()?.let { result ->
            if (result.generation == generation) {
                val box = result.imageBox?.let { imageBox ->
                    PrivacySegmentation.Box(
                        layout.left + imageBox.left * layout.resizedWidth / width,
                        layout.top + imageBox.top * layout.resizedHeight / height,
                        layout.left + imageBox.right * layout.resizedWidth / width,
                        layout.top + imageBox.bottom * layout.resizedHeight / height)
                }
                val match = result.embedding?.takeIf {
                    box != null && tracking.recognitionMatches(result.trackId, box)
                }?.let { if (optimized) PrivacyFaceMath.match(it, entries) else PrivacyFaceMath.matchReference(it, entries) }
                tracking.accept(result.trackId, match, result.capturedAtSeconds, time,
                    sampleAvailable = result.sampleAvailable)
                stats?.let { when {
                    !result.sampleAvailable -> it.noSample++
                    match != null -> it.matched++
                    else -> it.unmatched++
                } }
            }
        }
        // Like innolive-ai #77, an approval survives while the face model is briefly unavailable.
        val allowed = if (entries.isEmpty()) emptySet() else tracking.allowed()
        if (entries.isNotEmpty()) stats?.frame(objects, allowed)
        return allowed
    }

    private fun refreshRevision() {
        if (revision != revisionSource()) {
            revision = revisionSource()
            cachedEntries = null
            // In-flight results compared against the old list are dropped; approvals of remaining faces stay.
            generation++
            tracking.retain(entries().mapTo(HashSet()) { it.id })
        }
    }

    private fun entries(): List<PrivacyRegisteredFace> = try {
        if (optimized) cachedEntries ?: registeredFaces().also { cachedEntries = it }
        else registeredFaces()
    } catch (_: Exception) {
        cachedEntries = null
        reset()
        emptyList()
    }

    /** The caller owns the returned pixels, including when the crop covers the whole frame. */
    internal fun recognitionCrop(upright: Bitmap, box: PrivacySegmentation.Box,
                                layout: PrivacySegmentation.Letterbox): Bitmap? {
        return recognitionBounds(upright.width, upright.height, box, layout)?.let { copyCrop(upright, it) }
    }

    private fun recognitionBounds(width: Int, height: Int, box: PrivacySegmentation.Box,
                                  layout: PrivacySegmentation.Letterbox): Rect? {
        val real = toImageBox(box, layout, width, height) ?: return null
        if (min(real.width, real.height) < MIN_FACE_PIXELS) return null
        // Server's YOLO crop margin is 0.25 on each side.
        val marginX = real.width * .25f
        val marginY = real.height * .25f
        val x0 = max(0, floor(real.left - marginX).toInt())
        val y0 = max(0, floor(real.top - marginY).toInt())
        val x1 = min(width, ceil(real.right + marginX).toInt())
        val y1 = min(height, ceil(real.bottom + marginY).toInt())
        if (x1 <= x0 || y1 <= y0) return null
        return Rect(x0, y0, x1, y1)
    }

    private fun copyCrop(upright: Bitmap, bounds: Rect): Bitmap {
        // createBitmap(source, ...) can return source for a full-frame immutable bitmap.
        val crop = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        try {
            Canvas(crop).drawBitmap(upright, bounds,
                Rect(0, 0, crop.width, crop.height), null)
            return crop
        } catch (error: Throwable) {
            crop.recycle()
            throw error
        }
    }

    private fun toImageBox(box: PrivacySegmentation.Box, layout: PrivacySegmentation.Letterbox,
                           width: Int, height: Int): PrivacySegmentation.Box? {
        val left = ((box.left - layout.left) * width / layout.resizedWidth).coerceIn(0f, width.toFloat())
        val top = ((box.top - layout.top) * height / layout.resizedHeight).coerceIn(0f, height.toFloat())
        val right = ((box.right - layout.left) * width / layout.resizedWidth).coerceIn(0f, width.toFloat())
        val bottom = ((box.bottom - layout.top) * height / layout.resizedHeight).coerceIn(0f, height.toFloat())
        return if (right > left && bottom > top) PrivacySegmentation.Box(left, top, right, bottom) else null
    }

    companion object {
        /** Faces shorter than innolive-ai's registration minimum are left blurred instead of recognized. */
        const val MIN_FACE_PIXELS = 40
        const val MAX_RECOGNITIONS_PER_SECOND = 2
    }
}
