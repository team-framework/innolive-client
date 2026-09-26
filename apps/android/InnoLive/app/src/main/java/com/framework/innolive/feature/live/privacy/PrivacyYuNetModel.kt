package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class YuNetFace(val box: RectF, val landmarks: List<PointF>, val score: Float) {
    fun square(): RectF {
        val side = max(box.width(), box.height()) * 1.5f
        return RectF(box.centerX() - side / 2, box.centerY() - side / 2,
            box.centerX() + side / 2, box.centerY() + side / 2)
    }

    fun normalizedLandmarks(): FloatArray {
        val square = square()
        return FloatArray(10) { index ->
            val point = landmarks[index / 2]
            val value = if (index % 2 == 0) (point.x - square.left) / square.width()
            else (point.y - square.top) / square.height()
            value.coerceIn(0f, 1f)
        }
    }
}

internal class AmbiguousFaceSampleException : IllegalStateException("Multiple faces in recognition crop")

/** YuNet output decoding follows OpenCV FaceDetectorYN and the iOS implementation. */
internal object PrivacyYuNetDecoding {
    fun decode(outputs: Map<String, FloatArray>, width: Int, height: Int): List<YuNetFace> {
        require(width > 0 && height > 0 && width % 32 == 0 && height % 32 == 0)
        val found = mutableListOf<YuNetFace>()
        for (stride in listOf(8, 16, 32)) {
            val columns = width / stride
            val count = columns * (height / stride)
            val cls = checkNotNull(outputs["cls_$stride"]).also { require(it.size == count) }
            val obj = checkNotNull(outputs["obj_$stride"]).also { require(it.size == count) }
            val bbox = checkNotNull(outputs["bbox_$stride"]).also { require(it.size == count * 4) }
            val kps = checkNotNull(outputs["kps_$stride"]).also { require(it.size == count * 10) }
            require(cls.all(Float::isFinite) && obj.all(Float::isFinite) &&
                bbox.all(Float::isFinite) && kps.all(Float::isFinite))
            for (index in 0 until count) {
                val score = sqrt(cls[index].coerceIn(0f, 1f) * obj[index].coerceIn(0f, 1f))
                if (score < 0.6f) continue
                val row = index / columns
                val col = index % columns
                val centerX = (col + bbox[index * 4]) * stride
                val centerY = (row + bbox[index * 4 + 1]) * stride
                val w = exp(bbox[index * 4 + 2]) * stride
                val h = exp(bbox[index * 4 + 3]) * stride
                if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0 ||
                    w >= 100_000 || h >= 100_000 ||
                    !centerX.isFinite() || !centerY.isFinite() ||
                    kotlin.math.abs(centerX) >= 100_000 || kotlin.math.abs(centerY) >= 100_000) continue
                val landmarks = List(5) { point ->
                    PointF((kps[index * 10 + point * 2] + col) * stride,
                        (kps[index * 10 + point * 2 + 1] + row) * stride)
                }
                found += YuNetFace(RectF(centerX - w / 2, centerY - h / 2,
                    centerX + w / 2, centerY + h / 2), landmarks, score)
            }
        }
        val kept = mutableListOf<YuNetFace>()
        for (candidate in found.sortedByDescending { it.score }.take(5000)) {
            if (kept.none { integerIoU(it.box, candidate.box) > 0.3f }) kept += candidate
        }
        return kept
    }

    private fun integerIoU(a: RectF, b: RectF): Float {
        val left = max(a.left.toInt(), b.left.toInt())
        val top = max(a.top.toInt(), b.top.toInt())
        val right = min(a.left.toInt() + a.width().toInt(), b.left.toInt() + b.width().toInt())
        val bottom = min(a.top.toInt() + a.height().toInt(), b.top.toInt() + b.height().toInt())
        val intersection = max(0, right - left) * max(0, bottom - top)
        val areaA = a.width().toInt() * a.height().toInt()
        val areaB = b.width().toInt() * b.height().toInt()
        return intersection.toFloat() / max(1, areaA + areaB - intersection)
    }
}

internal class PrivacyYuNetModel(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open("privacy-yunet.onnx").use { it.readBytes() }
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        } == "4514febaf280cb408b6bfcd240df4463ba5295713e571c1133d743ff4eb1d737")
        OrtSession.SessionOptions().use { options ->
            session = environment.createSession(bytes, options)
        }
        try {
            check(session.inputNames == setOf("input"))
            check(session.outputNames == (listOf(8, 16, 32).flatMap { stride ->
                listOf("cls_$stride", "obj_$stride", "bbox_$stride", "kps_$stride")
            }).toSet())
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    fun oneFace(image: Bitmap, enrollment: Boolean): YuNetFace? {
        val scale = if (enrollment) 1f else min(1f, 320f / max(image.width, image.height))
        val resizedWidth = max(1, (image.width * scale).roundToInt())
        val resizedHeight = max(1, (image.height * scale).roundToInt())
        val width = ceil(resizedWidth / 32f).toInt() * 32
        val height = ceil(resizedHeight / 32f).toInt() * 32
        require(width <= 2048 && height <= 2048)
        val resized = Bitmap.createScaledBitmap(image, resizedWidth, resizedHeight, true)
        try {
            val pixels = IntArray(resizedWidth * resizedHeight)
            resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, resizedHeight)
            val plane = width * height
            val input = FloatArray(3 * plane)
            for (y in 0 until resizedHeight) for (x in 0 until resizedWidth) {
                val pixel = pixels[y * resizedWidth + x]
                val index = y * width + x
                input[index] = Color.blue(pixel).toFloat()
                input[plane + index] = Color.green(pixel).toFloat()
                input[2 * plane + index] = Color.red(pixel).toFloat()
            }
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
                session.run(mapOf("input" to tensor)).use { result ->
                    val outputs = session.outputNames.associateWith { name ->
                        val buffer = (result[name].orElseThrow() as OnnxTensor).floatBuffer
                        FloatArray(buffer.remaining()).also(buffer::get)
                    }
                    val faces = PrivacyYuNetDecoding.decode(outputs, width, height)
                        .filter { it.score >= if (enrollment) 0.9f else 0.6f }
                    if (faces.size > 1) throw AmbiguousFaceSampleException()
                    if (faces.isEmpty()) return null
                    val face = faces.single()
                    val restoreX = image.width.toFloat() / resizedWidth
                    val restoreY = image.height.toFloat() / resizedHeight
                    val restored = YuNetFace(RectF(face.box.left * restoreX, face.box.top * restoreY,
                        face.box.right * restoreX, face.box.bottom * restoreY),
                        face.landmarks.map { PointF(it.x * restoreX, it.y * restoreY) }, face.score)
                    if (min(restored.box.width(), restored.box.height()) < if (enrollment) 40f else 24f) return null
                    return restored
                }
            }
        } finally {
            if (resized !== image) resized.recycle()
        }
    }

    override fun close() { session.close() }
}
