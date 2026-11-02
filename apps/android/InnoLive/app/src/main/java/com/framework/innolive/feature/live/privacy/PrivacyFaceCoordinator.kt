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

/** 직렬 카메라 분석기가 소유하며, worker는 결과 보관함 하나로 결과를 돌려줍니다. */
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
    /** 최근 1초 동안 AdaFace에 제출한 캡처 시각입니다. */
    private val recentSubmissions = ArrayDeque<Double>()
    /** 승인된 얼굴이 잠시 검출되지 않는 동안에도 계속 따라가는 BoT-SORT ID입니다. */
    var liveTrackIds: () -> Set<Int>? = { null }
    private val stats = if (BuildConfig.DEBUG || BuildConfig.PRIVACY_PERF_DIAGNOSTICS) Stats() else null

    /** 디버그 전용 인식·예외 횟수이며, [Stats.INTERVAL_NS]마다 로그로 남깁니다. */
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

    /** 보호된 프레임이 WebRTC에 들어가기 전에 비용이 큰 얼굴 모델 검증을 끝냅니다. */
    fun prewarmForUplink() {
        refreshRevision()
        if (entries().isEmpty()) { releaseLease(); return }
        if (!leased) { service.retain(); leased = true }
        service.prepare()
        if (!service.awaitPreparation(20_000)) {
            android.util.Log.w("PrivacyFace", "prewarm_unavailable_blur_all=true")
        }
    }

    /** 독립된 인식 작업을 최대 하나만 시작합니다. 분석기는 결과를 기다리지 않습니다. */
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
        // AdaFace 실행마다 검출기와 GPU를 두고 경쟁하므로, 프레임 전체에 고정 예산을 둡니다.
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

    /** 이 프레임의 BoT-SORT 트랙에서 crop을 고르고, 검출기 GPU 작업 뒤에 인식을 시작합니다. */
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
        // innolive-ai #77처럼, 얼굴 모델을 잠시 쓸 수 없어도 승인은 유지됩니다.
        val allowed = if (entries.isEmpty()) emptySet() else tracking.allowed()
        if (entries.isNotEmpty()) stats?.frame(objects, allowed)
        return allowed
    }

    private fun refreshRevision() {
        if (revision != revisionSource()) {
            revision = revisionSource()
            cachedEntries = null
            // 이전 목록과 비교한 진행 중 결과는 버리고, 남은 얼굴의 승인은 유지합니다.
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

    /** crop이 프레임 전체를 덮는 경우를 포함해, 반환된 픽셀은 호출자가 소유합니다. */
    internal fun recognitionCrop(upright: Bitmap, box: PrivacySegmentation.Box,
                                layout: PrivacySegmentation.Letterbox): Bitmap? {
        return recognitionBounds(upright.width, upright.height, box, layout)?.let { copyCrop(upright, it) }
    }

    private fun recognitionBounds(width: Int, height: Int, box: PrivacySegmentation.Box,
                                  layout: PrivacySegmentation.Letterbox): Rect? {
        val real = toImageBox(box, layout, width, height) ?: return null
        if (min(real.width, real.height) < MIN_FACE_PIXELS) return null
        // 서버의 YOLO crop 여백은 각 변마다 0.25입니다.
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
        // createBitmap(source, ...)은 전체 프레임 변경 불가 비트맵이면 source를 그대로 반환할 수 있습니다.
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
        /** innolive-ai의 등록 최소 크기보다 작은 얼굴은 인식하지 않고 블러로 둡니다. */
        const val MIN_FACE_PIXELS = 40
        const val MAX_RECOGNITIONS_PER_SECOND = 2
    }
}
