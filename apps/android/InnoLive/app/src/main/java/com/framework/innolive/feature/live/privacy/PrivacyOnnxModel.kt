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
                                private val directGpuInput:Boolean=true,
                               private val sessionOptions: () -> OrtSession.SessionOptions = { OrtSession.SessionOptions() },
                               private val nativePostprocessing: Boolean = true,
                               private val batchTwoOptimizations: Boolean = true,
                               /** Detect every N frames; BoT-SORT predicts the objects of the frames between. */
                               private val detectionInterval: Int = DETECTION_INTERVAL,
                               /** Session animal (`rabbit`/`owl`) fixed at session creation; null keeps blur only. */
                               animal: String? = null) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val blur = PrivacyBitmapBlur()
    private val stabilizer = PrivacyMaskStabilizer()
    private val tracking = PrivacyObjectTracking()
    /** BoT-SORT IDs of the latest frame that a registered-face approval may keep following. */
    val liveTrackIds: Set<Int> get() = tracking.liveTrackIds
    private var skippedDetections = 0
    private var lastPrototypes: PrivacyValidatedPrototypes? = null
    private val postprocessorDelegate = lazy { PrivacyNativePostprocessor() }
    private val postprocessor by postprocessorDelegate
    var lastMaskPixels: Int = -1
        private set
    private val inputBitmapDelegate = lazy { Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888) }
    private val inputBitmap by inputBitmapDelegate
    private val inputCanvas by lazy { Canvas(inputBitmap) }
    private val inputBytes by lazy { ByteBuffer.allocateDirect(3 * 640 * 640 * 4).order(ByteOrder.nativeOrder()) }
    private val inputTensorDelegate = lazy { OnnxTensor.createTensor(environment, inputBytes.asFloatBuffer(), longArrayOf(1, 3, 640, 640)) }
    private val inputTensor by inputTensorDelegate
    private val gpuInput by lazy { FloatArray(3 * 640 * 640) }
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
        cpuSession()
        if (!batchTwoOptimizations) { inputTensor; inputBitmap; gpuInput }
    }

    private fun cpuSession(): OrtSession {
        session?.let { return it }
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        check(sha256(bytes) == MODEL_SHA256) { "Privacy model checksum mismatch" }
        val candidate = sessionOptions().use { environment.createSession(bytes, it) }
        try {
            check(candidate.inputNames == setOf("images"))
            check((candidate.outputInfo["output0"]?.info as? TensorInfo)?.shape
                ?.contentEquals(longArrayOf(1, 38, 8400)) == true)
            check((candidate.outputInfo["output1"]?.info as? TensorInfo)?.shape
                ?.contentEquals(longArrayOf(1, 32, 160, 160)) == true)
            session = candidate
            return candidate
        } catch (error: Throwable) { candidate.close(); throw error }
    }

    internal val retainsCpuSession: Boolean get() = session != null

    private val animalSprite = PrivacyAnimalSprite(context, animal)
    private var animalFaces: List<PrivacyAnimalFace> = emptyList()
    fun animalOverlay() = animalSprite.asset()?.let { PrivacyAnimalOverlay(it.first, it.second, animalFaces) }
    internal val animalCost get() = animalSprite

    fun process(
        upright: Bitmap,
        timestampNs: Long = System.nanoTime(),
        mosaic: PrivacyMosaic = PrivacyMosaic(),
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
        return processPrepared(inputBitmap, layout, timestampNs, exemptFaces, poseSource = upright) { mask ->
            val sigma = mosaic.sigma(upright.width, upright.height)
            val protected = PrivacyMaskRenderer.render(upright, mask, layout, { blur.apply(it, sigma) },
                mosaic.edgeCells(upright.width, upright.height))
            val output = animalSprite.composite(protected, animalFaces, mask, layout, mosaic.edgeCells(upright.width, upright.height))
            if (output !== protected) protected.recycle()
            output
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
        poseSource: Bitmap? = null,
        render: (ByteArray) -> T,
    ): T {
        require(preparedInput.width == 640 && preparedInput.height == 640)
        val started = System.nanoTime()
        if (!gpuChecked) {
            gpuChecked = true
            if (batchTwoOptimizations && directGpuInput && gpuGraph != null) {
                gpuGraph.validateNativeInput(context, diagnosticBenchmark = false) { reference(it).first }
            }
            if (gpuGraph?.nativeInputEnabled != true) {
                gpu = PrivacyDetectorGpuEngine.validated(context, ::reference)
                nnapi = PrivacyNnapiDetectorEngine.validated(context, { reference(it).first }) { pixels ->
                    gpu?.predict(pixels) ?: reference(pixels).first
                }
            }
        }
        if (nnapi == null && directGpuInput) gpuGraph?.validateNativeInput(context,
            diagnosticBenchmark = !batchTwoOptimizations) { pixels ->
            gpu?.predict(pixels) ?: reference(pixels).first
        }
        if (batchTwoOptimizations && gpuGraph?.nativeInputEnabled == true) {
            session?.close(); session = null
        }
        nativeInputActive = gpuGraph?.nativeInputEnabled == true
        val time = timestampNs / 1_000_000_000.0
        val predicted = if (skippedDetections + 1 < detectionInterval &&
            (nativePostprocessing || lastPrototypes != null)) tracking.predict(time) else null
        skippedDetections = if (predicted != null) skippedDetections + 1 else 0
        if (!nativeInputActive && predicted == null) PrivacyNativePixels.bitmapToTensor(preparedInput, inputBytes)
        val prepared = System.nanoTime()
        var nativeOutputs = false
        var nativeDetections: List<PrivacySegmentation.Detection>? = null
        val legacyOutputs = if (predicted != null) null else if (nativeInputActive) try {
            if (nativePostprocessing) {
                checkNotNull(gpuGraph).predictNativeInputInto(postprocessor.predictions, postprocessor.prototypes)
                nativeDetections = postprocessor.decode()
                nativeOutputs = true
                null
            } else checkedOutput(checkNotNull(gpuGraph).predictNativeInput())
        } catch (error: Throwable) {
            if (!error.isPrivacyProcessingFailure()) throw error
            nativeInputActive = false
            checkNotNull(gpuGraph).disableNativeInput()
            PrivacyNativePixels.bitmapToTensor(preparedInput, inputBytes)
            Log.w("PrivacyDetector", "gpu_input_runtime_fallback type=${error.javaClass.simpleName}")
            predictLegacy()
        } else predictLegacy()
        val inferred = System.nanoTime()
        run {
            val objects = predicted ?: tracking.update(if (nativePostprocessing) {
                if (!nativeOutputs) {
                    val values = checkNotNull(legacyOutputs)
                    postprocessor.predictions.asFloatBuffer().put(values.first)
                    // The legacy path has already validated this immutable array.
                    values.second.copyInto(postprocessor.prototypes)
                }
                if (nativeOutputs) checkNotNull(nativeDetections) else postprocessor.decode()
            } else PrivacySegmentation.detections(checkNotNull(legacyOutputs).first), time)
            if (nativePostprocessing) postprocessor.select(objects)
            val beforeFaces = System.nanoTime()
            val exempt = exemptFaces(objects, layout)
            val afterFaces = System.nanoTime()
            val mask = if (nativePostprocessing) {
                postprocessor.mask(exempt, timestampNs / 1_000_000_000.0).also { lastMaskPixels = postprocessor.maskPixels }
            } else {
                val prototypes = legacyOutputs?.second?.also { lastPrototypes = it } ?: checkNotNull(lastPrototypes)
                val instances = PrivacyNativeSegmentation.instanceMasks(
                    PrivacySegmentation.protectedDetections(objects, exempt), prototypes)
                stabilizer.apply(instances, timestampNs / 1_000_000_000.0).also { lastMaskPixels = -1 }
            }
            // Head pose reads the current upright frame: the CPU bitmap, or a small crop of the GPU graph's texture.
            animalSprite.beginFrame()
            animalFaces = if (animalSprite.asset() == null) emptyList() else animalSprite.faces(
                PrivacySegmentation.protectedDetections(objects, exempt).filter { it.classId == 0 }, layout,
                poseSource?.let { upright -> { square: RectF -> animalSprite.crop(upright, square) } }
                    ?: gpuGraph?.let { graph -> { square: RectF -> graph.poseCrop(square, PrivacyAnimalSprite.POSE_INPUT) } })
            val masked = System.nanoTime()
            val output = render(mask)
            val rendered = System.nanoTime()
            if (BuildConfig.DEBUG || BuildConfig.PRIVACY_PERF_DIAGNOSTICS) {
                val maskPixels = if (lastMaskPixels >= 0) lastMaskPixels else mask.count { it.toInt() != 0 }
                lastAnalysis = PrivacyFrameAnalysis(usesGpu, objects.count { it.classId == 0 },
                    objects.count { it.classId == 1 }, exempt.size,
                    maskPixels, maskPixels > 0 && (renderOnGpu || blur.lastUsedGpu), nnapi != null,
                    nativeInputActive, nativeInputActive && gpuGraph?.nativeInputUsesManagedSync == true,
                    detectionSkipped = predicted != null)
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

    fun resetTemporalState() {
        animalFaces = emptyList(); animalSprite.reset()
        stabilizer.reset(); tracking.reset(); skippedDetections = 0; lastPrototypes = null
        if (postprocessorDelegate.isInitialized()) postprocessor.reset()
    }

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
        } catch (error: Throwable) {
            if (!error.isPrivacyProcessingFailure()) throw error
            if (nnapi != null) {
                nnapi?.close(); nnapi = null
                Log.w("PrivacyDetector", "nnapi_runtime_fallback type=${error.javaClass.simpleName}")
                try {
                    checkedOutput(gpu?.predict(gpuInput,validateOutput=false) ?: runOnnx())
                } catch (fallbackError: Throwable) {
                    if (!fallbackError.isPrivacyProcessingFailure()) throw fallbackError
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
        cpuSession().run(mapOf("images" to inputTensor)).use { result ->
            val predictions = (result["output0"].orElseThrow() as OnnxTensor).floatBuffer
            val prototypes = (result["output1"].orElseThrow() as OnnxTensor).floatBuffer
            FloatArray(predictions.remaining()).also(predictions::get) to
                FloatArray(prototypes.remaining()).also(prototypes::get)
        }

    private fun reference(pixels: FloatArray): Pair<Pair<FloatArray, FloatArray>, Long> =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, 640, 640)).use { tensor ->
            val started = System.nanoTime()
            val output = cpuSession().run(mapOf("images" to tensor)).use { result ->
                val predictions = (result["output0"].orElseThrow() as OnnxTensor).floatBuffer
                val prototypes = (result["output1"].orElseThrow() as OnnxTensor).floatBuffer
                FloatArray(predictions.remaining()).also(predictions::get) to
                    FloatArray(prototypes.remaining()).also(prototypes::get)
            }
            output to (System.nanoTime() - started)
        }

    override fun close() {
        animalSprite.close()
        if (postprocessorDelegate.isInitialized()) postprocessor.close()
        releaseAccelerators()
        blur.close()
        if (inputTensorDelegate.isInitialized()) inputTensor.close()
        if (inputBitmapDelegate.isInitialized()) inputBitmap.recycle()
        session?.close(); session = null
    }

    internal fun releaseAccelerators() {
        nnapi?.close(); nnapi = null
        gpu?.close(); gpu = null
        nativeInputActive = false
    }

    companion object {
        /** Every other frame skips YOLO while every object is a confirmed, detected track. */
        const val DETECTION_INTERVAL = 2
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
    val directGpuInput: Boolean = false, val inputInteropSync: Boolean = false,
    val detectionSkipped: Boolean = false,
)
