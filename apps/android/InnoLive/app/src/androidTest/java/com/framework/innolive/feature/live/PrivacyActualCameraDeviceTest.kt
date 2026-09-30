package com.framework.innolive.feature.live

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import com.framework.innolive.feature.live.privacy.PrivacyCameraPeer
import com.framework.innolive.feature.live.privacy.PrivacyFaceCoordinator
import com.framework.innolive.feature.live.privacy.PrivacyFaceService
import com.framework.innolive.feature.live.privacy.PrivacyRegisteredFace
import com.framework.innolive.feature.live.privacy.PrivacyTextureReadbackCounter
import org.webrtc.*
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real camera + production AI. Optional host ICE encode/decode; no HTTP or saved images. */
@RunWith(AndroidJUnit4::class)
class PrivacyActualCameraDeviceTest {
    @androidx.camera.core.ExperimentalGetImage
    @Test fun measureActualCameraLocallyAt1080pAnd720p() {
        assumeFalse(android.os.Build.MODEL.startsWith("sdk_") || android.os.Build.HARDWARE.contains("ranchu"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val optimized=InstrumentationRegistry.getArguments().getString("privacyOptimized","true").toBoolean()
        val batchOne=InstrumentationRegistry.getArguments().getString("privacyBatchOne","true").toBoolean()
        val pendingLatest=InstrumentationRegistry.getArguments().getString("privacyPendingLatest","true").toBoolean()
        val performancePreview=InstrumentationRegistry.getArguments().getString("privacyPreviewPerformance","false").toBoolean()
        val localPeer = InstrumentationRegistry.getArguments().getString("privacyLocalPeer", "false").toBoolean()
        val recognizeFace = InstrumentationRegistry.getArguments().getString("privacyRecognizeFace", "false").toBoolean()
        val preferHardware = InstrumentationRegistry.getArguments().getString("privacyHardwareCodec", "true").toBoolean()
        val durationMs = InstrumentationRegistry.getArguments().getString("privacyDurationMs", "25000").toLong()
        val onlyHeight = InstrumentationRegistry.getArguments().getString("privacyHeight")?.toInt()
        val context = instrumentation.targetContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        }
        assertEquals("Camera permission was not granted", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions())
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var activity: ComponentActivity? = null
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity = it
            }
            val host = checkNotNull(activity)
            for ((width, height) in listOf(1920 to 1080, 1280 to 720).filter { onlyHeight == null || it.second == onlyHeight }) {
                val egl = EglBase.create()
                lateinit var previewView: PreviewView
                lateinit var renderer: SurfaceViewRenderer
                instrumentation.runOnMainSync {
                    val root = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.BLACK)
                    }
                    root.addView(TextView(host).apply {
                        text = "기기 AI 테스트 · ${width}×$height\n위: 원본 / 아래: AI 처리\n얼굴·번호판을 비춰 주세요. 영상은 저장되지 않습니다."
                        setTextColor(Color.WHITE)
                        textSize = 16f
                    })
                    previewView = PreviewView(host).apply {
                        implementationMode = if(performancePreview) PreviewView.ImplementationMode.PERFORMANCE
                            else PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                    }
                    root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
                    renderer = SurfaceViewRenderer(host).apply {
                        init(egl.eglBaseContext, null)
                        setMirror(true)
                    }
                    root.addView(renderer, LinearLayout.LayoutParams(-1, 0, 1f))
                    host.setContentView(root)
                }
                val collecting = AtomicBoolean(false)
                val captures = AtomicInteger()
                val failures = AtomicInteger()
                val ready = CountDownLatch(1)
                val samples = ConcurrentLinkedQueue<PrivacyCaptureDiagnostics>()
                val observedSize = ConcurrentLinkedQueue<Pair<Int, Int>>()
                val textureFrames = AtomicInteger()
                val hardwareBufferInspected = AtomicBoolean(false)
                val decoded = AtomicInteger()
                val decodedGaps = ConcurrentLinkedQueue<Double>()
                var lastDecodedNs = 0L
                val factory = if (localPeer) PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory() else null
                val source = factory?.createVideoSource(false)
                val track = factory?.createVideoTrack("camera-protected",checkNotNull(source))
                val sender = factory?.let { PrivacyCameraPeer(it) {} }
                val receiver = factory?.let { PrivacyCameraPeer(it) { frame ->
                    renderer.onFrame(frame)
                    if (collecting.get()) {
                        val now = System.nanoTime()
                        if (lastDecodedNs != 0L) decodedGaps.add((now-lastDecodedNs)/1e6)
                        lastDecodedNs = now
                        decoded.incrementAndGet()
                    }
                } }
                if (sender != null && receiver != null) {
                    val transceiver = checkNotNull(sender.connection.addTransceiver(checkNotNull(track),
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)))
                    if (preferHardware) {
                        val codecs = checkNotNull(preferredHardwareVideoCodecs(
                            checkNotNull(factory).getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs,
                            HardwareVideoEncoderFactory(egl.eglBaseContext,true,true).supportedCodecs.toList()))
                        assertTrue(transceiver.setCodecPreferences(codecs).isSuccess())
                    }
                    sender.local(sender.create(true))
                    assertTrue(sender.gathered.await(5,TimeUnit.SECONDS))
                    receiver.remote(checkNotNull(sender.connection.localDescription))
                    receiver.local(receiver.create(false))
                    assertTrue(receiver.gathered.await(5,TimeUnit.SECONDS))
                    sender.remote(checkNotNull(receiver.connection.localDescription))
                    assertTrue(sender.connected.await(8,TimeUnit.SECONDS))
                    assertTrue(receiver.connected.await(8,TimeUnit.SECONDS))
                }
                fun stats(): Map<String,Any> {
                    val pc = sender?.connection ?: return emptyMap()
                    val done = CountDownLatch(1)
                    var values: Map<String,Any> = emptyMap()
                    pc.getStats { report ->
                        val outbound=report.statsMap.values.firstOrNull { it.type=="outbound-rtp" &&
                            (it.members["kind"]=="video" || it.members["mediaType"]=="video") }
                        outbound?.let { values = it.members.toMutableMap().apply {
                            put("mimeType", report.statsMap[it.members["codecId"]]?.members?.get("mimeType") ?: "unknown")
                        } }
                        done.countDown()
                    }
                    assertTrue(done.await(5,TimeUnit.SECONDS))
                    return values
                }
                val analyzer = CameraFrameAnalyzer(object : CapturerObserver {
                    override fun onCapturerStarted(success: Boolean) { source?.capturerObserver?.onCapturerStarted(success) }
                    override fun onCapturerStopped() { source?.capturerObserver?.onCapturerStopped() }
                    override fun onFrameCaptured(frame: VideoFrame) {
                        observedSize.add(frame.buffer.width to frame.buffer.height)
                        if (collecting.get() && frame.buffer is VideoFrame.TextureBuffer) textureFrames.incrementAndGet()
                        if (source != null) source.capturerObserver.onFrameCaptured(frame) else renderer.onFrame(frame)
                        ready.countDown()
                    }
                }, context, initialOnDevice = true, sharedEglContext = egl.eglBaseContext, onProcessingFailure = {
                    failures.incrementAndGet(); ready.countDown()
                },directCameraInput=optimized,directGpuInput=optimized,nativePostprocessing=batchOne,batchTwoOptimizations=
                    InstrumentationRegistry.getArguments().getString("privacyBatchTwo", "true").toBoolean(),
                    keepLatestProtectedFrame=pendingLatest).apply {
                    faceCoordinatorFactory=if (recognizeFace) ({
                        PrivacyFaceCoordinator(PrivacyFaceService.get(context),
                            revisionSource = { 0L }) {
                            listOf(PrivacyRegisteredFace("fps-fixture", "fps-fixture",
                                FloatArray(512).apply {this[0]=1f}, 0L))
                        }
                    }) else null
                }
                analyzer.onFrameDiagnostics = { if (collecting.get()) samples.add(it) }
                val executor = Executors.newSingleThreadExecutor()
                val selector = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_NONE)).build()
                val preview = Preview.Builder().setResolutionSelector(selector)
                    .setTargetRotation(Surface.ROTATION_0).build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(selector)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
                analyzer.start()
                try {
                    instrumentation.runOnMainSync {
                        preview.surfaceProvider = previewView.surfaceProvider
                        analysis.setAnalyzer(executor) { image ->
                            if(hardwareBufferInspected.compareAndSet(false,true)) {
                                image.image?.hardwareBuffer.use { buffer ->
                                    Log.i("PrivacyActualCamera","camera_hardware_buffer size=${width}x$height available=${buffer!=null} " +
                                        "format=${buffer?.format} usage=${buffer?.usage} dimensions=${buffer?.width}x${buffer?.height}")
                                }
                            }
                            if (collecting.get()) captures.incrementAndGet()
                            analyzer.analyze(image)
                        }
                        val viewPort=ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0)
                            .setScaleType(ViewPort.FILL_CENTER).build()
                        val fixed30=CameraFrameRateBinding.bind(provider,host,
                            CameraSelector.DEFAULT_FRONT_CAMERA,listOf(preview,analysis),viewPort)
                        Log.i("PrivacyActualCamera","size=${width}x$height fixed_capture_30=$fixed30")
                    }
                    assertTrue("No protected camera frame arrived", ready.await(30, TimeUnit.SECONDS))
                    if (recognizeFace) assertTrue(
                        "Face model was still warming while protected frames entered WebRTC",
                        PrivacyFaceService.get(context).ready,
                    )
                    assertEquals("AI frame processing failed", 0, failures.get())
                    Thread.sleep(3000) // Exclude compilation and initial exposure settling.
                    val initialStats = stats()
                    val readbacks = PrivacyTextureReadbackCounter.value()
                    val started = System.nanoTime()
                    collecting.set(true)
                    Thread.sleep(durationMs)
                    collecting.set(false)
                    val seconds = (System.nanoTime() - started) / 1e9
                    val measured = samples.toList()
                    val finalStats = stats()
                    fun delta(key:String) = ((finalStats[key] as? Number)?.toDouble() ?: 0.0) -
                        ((initialStats[key] as? Number)?.toDouble() ?: 0.0)
                    val encoded = delta("framesEncoded")
                    val decodeGaps = decodedGaps.sorted()
                    if (localPeer) assertTrue("No protected frames decoded over RTP", decoded.get() > 0)
                    assertTrue("GPU texture output was not used", textureFrames.get() > 0)
                    assertTrue("No steady AI samples", measured.isNotEmpty())
                    assertEquals(0, failures.get())
                    assertTrue("Requested camera geometry changed: ${observedSize.toSet()}",
                        observedSize.all { it == (width to height) })
                    assertTrue("Detector GPU was not used", measured.any { it.analysis.detectorGpu || it.analysis.detectorNnapi })
                    fun percentile(fraction: Double, select: (PrivacyCaptureDiagnostics) -> Double): Double {
                        val values = measured.map(select).sorted()
                        return values[(values.size * fraction).toInt().coerceAtMost(values.lastIndex)]
                    }
                    if(optimized) {
                        assertTrue("Camera planes were repacked",measured.all {it.cameraCopiedPlanes==0})
                        assertTrue("Direct model input was not active",measured.all {it.analysis.directGpuInput})
                        assertTrue("Runtime input interop sync was not active",measured.all {it.analysis.inputInteropSync})
                    }
                    val protected = measured.filter { it.analysis.maskPixels > 0 }
                    val ageMs=measured.map {it.frameAgeMs}.filter(Double::isFinite).sorted()
                    val ageP95=ageMs.getOrNull((ageMs.size*.95).toInt().coerceAtMost(ageMs.lastIndex)) ?: Double.NaN
                    fun protectedMedian(select: (PrivacyCaptureDiagnostics) -> Double): Double {
                        if (protected.isEmpty()) return Double.NaN
                        val values = protected.map(select).sorted()
                        return values[values.size / 2]
                    }
                    val metrics="local_peer=$localPeer recognize_face=$recognizeFace hardware_preferred=$preferHardware codec=${finalStats["mimeType"]} " +
                        "encoder=${finalStats["encoderImplementation"]} encoded_fps=${encoded/seconds} decoded_fps=${decoded.get()/seconds} " +
                        "encode_mean_ms=${if(encoded>0) delta("totalEncodeTime")*1000/encoded else Double.NaN} " +
                        "decoded_gap_p95_ms=${decodeGaps.getOrNull((decodeGaps.size*.95).toInt().coerceAtMost(decodeGaps.lastIndex))} " +
                        "decoded_gap_max_ms=${decodeGaps.lastOrNull()} texture_readbacks=${PrivacyTextureReadbackCounter.value()-readbacks} " +
                        "quality_limitation=${finalStats["qualityLimitationReason"]} " +
                        "optimized=$optimized batch_one=$batchOne pending_latest=$pendingLatest performance_preview=$performancePreview size=${width}x$height seconds=$seconds " +
                        "captures=${captures.get()} processed=${measured.size} " +
                        "capture_fps=${captures.get() / seconds} processed_fps=${measured.size / seconds} " +
                        "analysis_age_p95_ms=$ageP95 " +
                        "copy_p50_ms=${percentile(.5) { it.cameraCopyMs }} " +
                        "frame_p50_ms=${percentile(.5) { it.timings.totalMs }} " +
                        "frame_p95_ms=${percentile(.95) { it.timings.totalMs }} " +
                        "input_p50_ms=${percentile(.5) { it.timings.inputMs }} " +
                        "inference_p50_ms=${percentile(.5) { it.timings.model.inferenceMs }} " +
                        "render_p50_ms=${percentile(.5) { it.timings.model.renderMs }} " +
                        "protected_render_p50_ms=${protectedMedian { it.timings.model.renderMs }} " +
                        "protected_frame_p50_ms=${protectedMedian { it.timings.totalMs }} " +
                        "output_p50_ms=${percentile(.5) { it.timings.outputMs }} " +
                        "delivery_p50_ms=${percentile(.5) { it.deliveryMs }} " +
                        "zero_copy_planes_frames=${measured.count {it.cameraCopiedPlanes==0}} direct_input_frames=${measured.count {it.analysis.directGpuInput}} input_sync_frames=${measured.count {it.analysis.inputInteropSync}} " +
                        "gpu_frames=${measured.count { it.analysis.detectorGpu }} texture_frames=${textureFrames.get()} nnapi_frames=${measured.count { it.analysis.detectorNnapi }} " +
                        "face_frames=${measured.count { it.analysis.faces > 0 }} " +
                        "plate_frames=${measured.count { it.analysis.plates > 0 }} " +
                        "protected_frames=${measured.count { it.analysis.maskPixels > 0 }} " +
                        "blur_gpu_frames=${measured.count { it.analysis.maskPixels > 0 && it.analysis.blurGpu }} " +
                        "exempt_frames=${measured.count { it.analysis.exemptFaces > 0 }} errors=${failures.get()}"
                    Log.i("PrivacyActualCamera",metrics)
                    // Preserve measurements in the host runner output even if USB disconnects later.
                    instrumentation.sendStatus(2,android.os.Bundle().apply {putString("privacy_camera_metrics",metrics)})
                } finally {
                    instrumentation.runOnMainSync {
                        analysis.clearAnalyzer()
                        provider.unbind(preview, analysis)
                        analyzer.stop()
                        renderer.release()
                    }
                    executor.shutdownNow()
                    sender?.connection?.dispose(); receiver?.connection?.dispose()
                    track?.dispose(); source?.dispose(); factory?.dispose()
                    egl.release()
                }
            }
        }
    }
}
