package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Owned by the serial camera analyzer; the worker returns through a single result mailbox. */
internal class PrivacyFaceCoordinator(
    private val service: PrivacyFaceRecognitionService,
    private val optimized: Boolean = true,
    private val revisionSource: () -> Long = { PrivacyFaceLibrary.currentRevision },
    private val registeredFaces: () -> List<PrivacyRegisteredFace>,
) : AutoCloseable {
    constructor(context: Context, optimized: Boolean = true) : this(PrivacyFaceService.get(context), optimized,
        { PrivacyFaceLibrary.currentRevision },
        { PrivacyFaceService.get(context).library?.snapshot().orEmpty() })
    private val tracking = PrivacyFaceTracking()
    private var revision = revisionSource()
    private var generation = 0L
    private var previousLayout: PrivacySegmentation.Letterbox? = null
    private var geometry: Pair<Int, Int>? = null
    private var leased = false
    private var cachedEntries: List<PrivacyRegisteredFace>? = null

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
        val next = tracking.next(timestampNs / 1_000_000_000.0) ?: return
        val bounds = recognitionBounds(width, height, next.box, layout) ?: return
        if (readback != null) {
            val sample = readback(bounds)
            try {
                if (!service.submitReadback(sample, bounds, generation, next.id, timestampNs / 1_000_000_000.0)) sample.close()
            } catch (error: Throwable) { sample.close(); throw error }
            return
        }
        val image = crop(bounds)
        if (!service.submitRecognition(image, bounds, generation, next.id, timestampNs / 1_000_000_000.0)) image.recycle()
    }

    fun currentFrame(upright: Bitmap, objects: List<PrivacySegmentation.Detection>,
                     layout: PrivacySegmentation.Letterbox, timestampNs: Long): Set<Int> =
        currentFrame(upright.width,upright.height,objects,layout,timestampNs) { copyCrop(upright,it) }

    /** Select crops from this frame's YOLO tracks; start recognition after detector GPU work. */
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
        val faces = objects.indices.filter { objects[it].classId == 0 }
        tracking.update(faces.associateWith { objects[it].box }, time)
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
            }
        }
        if (entries.isEmpty() || !service.ready) return emptySet()
        return tracking.allowed(time)
    }

    private fun refreshRevision() {
        if (revision != revisionSource()) {
            revision = revisionSource()
            cachedEntries = null
            reset()
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
        if (min(real.width, real.height) < 24) return null
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
}
