package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
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
        val next = tracking.next(time)
        if (next != null) {
            val real = toImageBox(next.box, layout, upright.width, upright.height)
            if (real != null && min(real.width, real.height) >= 24) {
                // Server's YOLO crop margin is 0.25 on each side.
                val marginX = real.width * .25f
                val marginY = real.height * .25f
                val x0 = max(0, floor(real.left - marginX).toInt())
                val y0 = max(0, floor(real.top - marginY).toInt())
                val x1 = min(upright.width, ceil(real.right + marginX).toInt())
                val y1 = min(upright.height, ceil(real.bottom + marginY).toInt())
                if (x1 > x0 && y1 > y0) {
                    val crop = Bitmap.createBitmap(upright, x0, y0, x1 - x0, y1 - y0)
                    if (!service.submitRecognition(crop, generation, next.id, time)) crop.recycle()
                }
            }
        }
        return tracking.allowed(time)
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
