package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.framework.innolive.BuildConfig
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Owns the pinned YOLO ONNX session. The caller serializes access and never sends raw on error. */
internal class PrivacyOnnxModel(private val context: Context,
                               sessionOptions: () -> OrtSession.SessionOptions = { OrtSession.SessionOptions() }) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val blur = PrivacyBitmapBlur()
    private val stabilizer = PrivacyMaskStabilizer()
    private val inputBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputBitmap)
    private val inputBytes = ByteBuffer.allocateDirect(3 * 640 * 640 * 4).order(ByteOrder.nativeOrder())
    private val inputTensor: OnnxTensor
    private val gpuInput = FloatArray(3 * 640 * 640)
    private var gpuChecked = false
    private var gpu: PrivacyDetectorGpuEngine? = null
    private var nnapi: PrivacyNnapiDetectorEngine? = null
    private var nativeInputActive = false
    val usesGpu: Boolean get() = (gpu != null || nativeInputActive) && nnapi == null
    var lastAnalysis: PrivacyFrameAnalysis? = null
        private set
    var lastTimings: PrivacyModelTimings? = null
        private set

    init {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        check(sha256(bytes) == MODEL_SHA256) { "Privacy model checksum mismatch" }
        val options = sessionOptions()
        session = try { environment.createSession(bytes, options) } finally { options.close() }
        try {
            check(session.inputNames == setOf("images"))
            check((session.outputInfo["output0"]?.info as? TensorInfo)?.shape
                ?.contentEquals(longArrayOf(1, 38, 8400)) == true)
            check((session.outputInfo["output1"]?.info as? TensorInfo)?.shape
                ?.contentEquals(longArrayOf(1, 32, 160, 160)) == true)
            inputTensor = OnnxTensor.createTensor(environment, inputBytes.asFloatBuffer(), longArrayOf(1, 3, 640, 640))
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    fun process(
        upright: Bitmap,
        timestampNs: Long = System.nanoTime(),
        exemptFaces: (List<PrivacySegmentation.Detection>, PrivacySegmentation.Letterbox) -> Set<Int> = { _, _ -> emptySet() },
    ): Bitmap {
        val layout = PrivacySegmentation.Letterbox(upright.width, upright.height)
        val canvas = inputCanvas
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(
            upright,
            Rect(0, 0, upright.width, upright.height),
            RectF(layout.left.toFloat(), layout.top.toFloat(),
                (layout.left + layout.resizedWidth).toFloat(),
                (layout.top + layout.resizedHeight).toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return processPrepared(inputBitmap, layout, timestampNs, exemptFaces) { mask ->
            PrivacyMaskRenderer.render(upright, mask, layout, blur::apply)
        }
    }

    /** The image graph supplies only the fixed model input; no full-frame CPU bitmap is needed. */
    fun <T> processPrepared(
        preparedInput: Bitmap,
        layout: PrivacySegmentation.Letterbox,
        timestampNs: Long,
        exemptFaces: (List<PrivacySegmentation.Detection>, PrivacySegmentation.Letterbox) -> Set<Int>,
        renderOnGpu: Boolean = false,
        gpuGraph: PrivacyGpuFramePipeline? = null,
        render: (ByteArray) -> T,
    ): T {
        require(preparedInput.width == 640 && preparedInput.height == 640)
        val started = System.nanoTime()
        if (!gpuChecked) {
            gpuChecked = true
            gpu = PrivacyDetectorGpuEngine.validated(context, ::reference)
            nnapi = PrivacyNnapiDetectorEngine.validated(context, { reference(it).first }) { pixels ->
                gpu?.predict(pixels) ?: reference(pixels).first
            }
        }
        if (nnapi == null) gpuGraph?.validateNativeInput(context) { pixels ->
            gpu?.predict(pixels) ?: reference(pixels).first
        }
        nativeInputActive = gpuGraph?.nativeInputEnabled == true
        if (!nativeInputActive) PrivacyNativePixels.bitmapToTensor(preparedInput, inputBytes)
        val prepared = System.nanoTime()
        val (predictions, prototypes) = if (nativeInputActive) try {
            checkedOutput(checkNotNull(gpuGraph).predictNativeInput())
        } catch (error: Exception) {
            nativeInputActive = false
            checkNotNull(gpuGraph).disableNativeInput()
            PrivacyNativePixels.bitmapToTensor(preparedInput, inputBytes)
            Log.w("PrivacyDetector", "gpu_input_runtime_fallback type=${error.javaClass.simpleName}")
            predictLegacy()
        } else predictLegacy()
        val inferred = System.nanoTime()
        run {
            val objects = PrivacySegmentation.detections(predictions)
            val beforeFaces = System.nanoTime()
            val exempt = exemptFaces(objects, layout)
            val afterFaces = System.nanoTime()
            val instances = PrivacyNativeSegmentation.instanceMasks(
                PrivacySegmentation.protectedDetections(objects, exempt),
                prototypes,
            )
            val mask = stabilizer.apply(instances, timestampNs / 1_000_000_000.0)
            val masked = System.nanoTime()
            val output = render(mask)
            val rendered = System.nanoTime()
            if (BuildConfig.DEBUG) {
                val maskPixels = mask.count { it.toInt() != 0 }
                lastAnalysis = PrivacyFrameAnalysis(usesGpu, objects.count { it.classId == 0 },
                    objects.count { it.classId == 1 }, exempt.size,
                    maskPixels, maskPixels > 0 && (renderOnGpu || blur.lastUsedGpu), nnapi != null)
            }
            lastTimings = PrivacyModelTimings(
                (prepared - started) / 1e6, (inferred - prepared) / 1e6,
                (afterFaces - beforeFaces) / 1e6,
                (beforeFaces - inferred + masked - afterFaces) / 1e6,
                (rendered - masked) / 1e6,
            )
            return output
        }
    }

    fun resetTemporalState() { stabilizer.reset() }

    private fun checkedOutput(output: Pair<FloatArray,FloatArray>): Pair<FloatArray,PrivacyValidatedPrototypes> {
        check(PrivacyNativePixels.finiteFloats(output.first))
        return output.first to PrivacyValidatedPrototypes.validate(output.second)
    }

    private fun predictLegacy(): Pair<FloatArray, PrivacyValidatedPrototypes> {
        return try {
            val engine = gpu
            val output = if (nnapi != null) {
                inputBytes.asFloatBuffer().get(gpuInput)
                checkNotNull(nnapi).predict(gpuInput)
            } else if (engine == null) runOnnx()
            else {
                inputBytes.asFloatBuffer().get(gpuInput)
                engine.predict(gpuInput,validateOutput=false)
            }
            checkedOutput(output)
        } catch (error: Exception) {
            if (nnapi != null) {
                nnapi?.close(); nnapi = null
                Log.w("PrivacyDetector", "nnapi_runtime_fallback type=${error.javaClass.simpleName}")
                try {
                    checkedOutput(gpu?.predict(gpuInput,validateOutput=false) ?: runOnnx())
                } catch (_: Exception) {
                    runCatching { gpu?.close() }; gpu = null
                    checkedOutput(runOnnx())
                }
            } else {
                if (gpu == null) throw error
                runCatching { gpu?.close() }; gpu = null
                Log.w("PrivacyDetector", "gpu_runtime_fallback type=${error.javaClass.simpleName}")
                checkedOutput(runOnnx())
            }
        }
    }

    private fun runOnnx(): Pair<FloatArray, FloatArray> =
        session.run(mapOf("images" to inputTensor)).use { result ->
            val predictions = (result["output0"].orElseThrow() as OnnxTensor).floatBuffer
            val prototypes = (result["output1"].orElseThrow() as OnnxTensor).floatBuffer
            FloatArray(predictions.remaining()).also(predictions::get) to
                FloatArray(prototypes.remaining()).also(prototypes::get)
        }

    private fun reference(pixels: FloatArray): Pair<Pair<FloatArray, FloatArray>, Long> =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, 640, 640)).use { tensor ->
            val started = System.nanoTime()
            val output = session.run(mapOf("images" to tensor)).use { result ->
                val predictions = (result["output0"].orElseThrow() as OnnxTensor).floatBuffer
                val prototypes = (result["output1"].orElseThrow() as OnnxTensor).floatBuffer
                FloatArray(predictions.remaining()).also(predictions::get) to
                    FloatArray(prototypes.remaining()).also(prototypes::get)
            }
            output to (System.nanoTime() - started)
        }

    override fun close() {
        nnapi?.close(); nnapi = null
        gpu?.close(); gpu = null
        blur.close(); inputTensor.close(); inputBitmap.recycle(); session.close()
    }

    companion object {
        private const val MODEL_ASSET = "privacy-detector.onnx"
        private const val MODEL_SHA256 = "8d111ad2dcb5e5fa9d709f3d11606dcd62ea6f1d4833633864b1e4cf47f13be7"
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Numeric diagnostics only: no image, identity, embedding or endpoint is retained. */
internal data class PrivacyModelTimings(
    val prepareMs: Double, val inferenceMs: Double, val facesMs: Double,
    val maskMs: Double, val renderMs: Double,
)

/** Numeric diagnostics only; no face identity or camera pixels. */
internal data class PrivacyFrameAnalysis(
    val detectorGpu: Boolean, val faces: Int, val plates: Int, val exemptFaces: Int,
    val maskPixels: Int, val blurGpu: Boolean, val detectorNnapi: Boolean = false,
)
