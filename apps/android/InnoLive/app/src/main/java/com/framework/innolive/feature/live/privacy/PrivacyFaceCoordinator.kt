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
internal class PrivacyFaceCoordinator(context: Context) {
    private val service = PrivacyFaceService.get(context)
    private val tracking = PrivacyFaceTracking()
    private var revision = PrivacyFaceLibrary.currentRevision
    private var generation = 0L

    fun reset() {
        generation++
        tracking.reset()
    }

    fun exceptions(upright: Bitmap, objects: List<PrivacySegmentation.Detection>,
                   layout: PrivacySegmentation.Letterbox, timestampNs: Long): Set<Int> {
        val time = timestampNs / 1_000_000_000.0
        if (revision != PrivacyFaceLibrary.currentRevision) {
            revision = PrivacyFaceLibrary.currentRevision
            reset()
        }
        val faces = objects.indices.filter { objects[it].classId == 0 }
        tracking.update(faces.associateWith { objects[it].box }, time)
        val entries = try { service.library?.snapshot().orEmpty() } catch (_: Exception) {
            reset()
            emptyList()
        }
        service.takeResult()?.let { result ->
            if (result.generation == generation) {
                val match = result.embedding?.let { PrivacyFaceMath.match(it, entries) }
                tracking.accept(result.trackId, match, result.capturedAtSeconds, time,
                    sampleAvailable = result.sampleAvailable)
            }
        }
        if (entries.isEmpty()) return emptySet()
        service.prepare()
        if (!service.ready) return emptySet()
        val verified = tracking.verifyCurrentFrame(time) { candidate ->
            val crop = recognitionCrop(upright, candidate.box, layout)
            try {
                crop != null && service.verifyCurrentFace(crop, checkNotNull(candidate.candidate), entries)
            } finally { crop?.recycle() }
        }
        val next = tracking.next(time)
        if (next != null) {
            recognitionCrop(upright, next.box, layout)?.let { crop ->
                if (!service.submitRecognition(crop, generation, next.id, time)) crop.recycle()
            }
        }
        return verified
    }

    /** The caller owns the returned pixels, including when the crop covers the whole frame. */
    internal fun recognitionCrop(upright: Bitmap, box: PrivacySegmentation.Box,
                                layout: PrivacySegmentation.Letterbox): Bitmap? {
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
        // createBitmap(source, ...) can return source for a full-frame immutable bitmap.
        val crop = Bitmap.createBitmap(x1 - x0, y1 - y0, Bitmap.Config.ARGB_8888)
        try {
            Canvas(crop).drawBitmap(upright, Rect(x0, y0, x1, y1),
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
