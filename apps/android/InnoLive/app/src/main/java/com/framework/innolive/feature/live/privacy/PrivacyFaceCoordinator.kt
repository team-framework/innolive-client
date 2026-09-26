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
    private val registeredFaces: () -> List<PrivacyRegisteredFace>,
) {
    constructor(context: Context) : this(PrivacyFaceService.get(context),
        { PrivacyFaceService.get(context).library?.snapshot().orEmpty() })
    private val tracking = PrivacyFaceTracking()
    private var revision = PrivacyFaceLibrary.currentRevision
    private var generation = 0L
    private var previousLayout: PrivacySegmentation.Letterbox? = null
    private var geometry: Pair<Int, Int>? = null

    fun reset() {
        generation++
        tracking.reset()
        previousLayout = null
    }

    /** Start one current-frame job before YOLO. The analyzer never waits for recognition. */
    fun beginFrame(upright: Bitmap, timestampNs: Long) {
        refreshRevision()
        val dimensions = upright.width to upright.height
        if (geometry != dimensions) { reset(); geometry = dimensions }
        if (entries().isEmpty()) return
        service.prepare()
        if (!service.canSubmit) return
        val layout = previousLayout ?: return
        val next = tracking.next(timestampNs / 1_000_000_000.0, currentFrame = true) ?: return
        val bounds = recognitionBounds(upright, next.box, layout) ?: return
        val crop = copyCrop(upright, bounds)
        if (!service.submitRecognition(crop, bounds, generation, next.id, timestampNs / 1_000_000_000.0)) crop.recycle()
    }

    fun exceptions(upright: Bitmap, objects: List<PrivacySegmentation.Detection>,
                   layout: PrivacySegmentation.Letterbox, timestampNs: Long): Set<Int> {
        val time = timestampNs / 1_000_000_000.0
        refreshRevision()
        val faces = objects.indices.filter { objects[it].classId == 0 }
        tracking.update(faces.associateWith { objects[it].box }, time)
        previousLayout = layout
        val entries = entries()
        var verified = emptySet<Int>()
        service.takeResult()?.let { result ->
            if (result.generation == generation) {
                val match = result.embedding?.let { PrivacyFaceMath.match(it, entries) }
                tracking.accept(result.trackId, match, result.capturedAtSeconds, time,
                    sampleAvailable = result.sampleAvailable)
                val box = result.imageBox?.let { imageBox ->
                    PrivacySegmentation.Box(
                        layout.left + imageBox.left * layout.resizedWidth / upright.width,
                        layout.top + imageBox.top * layout.resizedHeight / upright.height,
                        layout.left + imageBox.right * layout.resizedWidth / upright.width,
                        layout.top + imageBox.bottom * layout.resizedHeight / upright.height)
                }
                verified = tracking.verifiedResult(result.trackId, match, result.capturedAtSeconds, time, box)
            }
        }
        if (entries.isEmpty()) return emptySet()
        return verified
    }

    private fun refreshRevision() {
        if (revision != PrivacyFaceLibrary.currentRevision) {
            revision = PrivacyFaceLibrary.currentRevision
            reset()
        }
    }

    private fun entries(): List<PrivacyRegisteredFace> = try { registeredFaces() } catch (_: Exception) {
        reset()
        emptyList()
    }

    /** The caller owns the returned pixels, including when the crop covers the whole frame. */
    internal fun recognitionCrop(upright: Bitmap, box: PrivacySegmentation.Box,
                                layout: PrivacySegmentation.Letterbox): Bitmap? {
        return recognitionBounds(upright, box, layout)?.let { copyCrop(upright, it) }
    }

    private fun recognitionBounds(upright: Bitmap, box: PrivacySegmentation.Box,
                                  layout: PrivacySegmentation.Letterbox): Rect? {
        val real = toImageBox(box, layout, upright.width, upright.height) ?: return null
        if (min(real.width, real.height) < 24) return null
        // Server's YOLO crop margin is 0.25 on each side.
        val marginX = real.width * .25f
        val marginY = real.height * .25f
        val x0 = max(0, floor(real.left - marginX).toInt())
        val y0 = max(0, floor(real.top - marginY).toInt())
        val x1 = min(upright.width, ceil(real.right + marginX).toInt())
        val y1 = min(upright.height, ceil(real.bottom + marginY).toInt())
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
