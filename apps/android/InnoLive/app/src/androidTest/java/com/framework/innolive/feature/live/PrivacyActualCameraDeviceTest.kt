package com.framework.innolive.feature.live

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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

/** Real camera + production protection + local renderer. No PeerConnection, HTTP or saved images. */
@RunWith(AndroidJUnit4::class)
class PrivacyActualCameraDeviceTest {
    @Test fun measureActualCameraLocallyAt1080pAnd720p() {
        assumeFalse(android.os.Build.MODEL.startsWith("sdk_") || android.os.Build.HARDWARE.contains("ranchu"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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
            scenario.onActivity { activity = it }
            val host = checkNotNull(activity)
            for ((width, height) in listOf(1920 to 1080, 1280 to 720)) {
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
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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
                val analyzer = CameraFrameAnalyzer(object : CapturerObserver {
                    override fun onCapturerStarted(success: Boolean) = Unit
                    override fun onCapturerStopped() = Unit
                    override fun onFrameCaptured(frame: VideoFrame) {
                        observedSize.add(frame.buffer.width to frame.buffer.height)
                        renderer.onFrame(frame)
                        ready.countDown()
                    }
                }, context, initialOnDevice = true, onProcessingFailure = {
                    failures.incrementAndGet(); ready.countDown()
                })
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
                            if (collecting.get()) captures.incrementAndGet()
                            analyzer.analyze(image)
                        }
                        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                            .setViewPort(ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0)
                                .setScaleType(ViewPort.FILL_CENTER).build()).build()
                        provider.bindToLifecycle(host, CameraSelector.DEFAULT_FRONT_CAMERA, group)
                    }
                    assertTrue("No protected camera frame arrived", ready.await(20, TimeUnit.SECONDS))
                    assertEquals("AI frame processing failed", 0, failures.get())
                    Thread.sleep(3000) // Exclude compilation and initial exposure settling.
                    val started = System.nanoTime()
                    collecting.set(true)
                    Thread.sleep(25_000)
                    collecting.set(false)
                    val seconds = (System.nanoTime() - started) / 1e9
                    val measured = samples.toList()
                    assertTrue("No steady AI samples", measured.isNotEmpty())
                    assertEquals(0, failures.get())
                    assertTrue("Requested camera geometry changed: ${observedSize.toSet()}",
                        observedSize.all { it == (width to height) })
                    assertTrue("Detector GPU was not used", measured.any { it.analysis.detectorGpu })
                    fun percentile(fraction: Double, select: (PrivacyCaptureDiagnostics) -> Double): Double {
                        val values = measured.map(select).sorted()
                        return values[(values.size * fraction).toInt().coerceAtMost(values.lastIndex)]
                    }
                    Log.i("PrivacyActualCamera", "size=${width}x$height seconds=$seconds " +
                        "captures=${captures.get()} processed=${measured.size} " +
                        "capture_fps=${captures.get() / seconds} processed_fps=${measured.size / seconds} " +
                        "copy_p50_ms=${percentile(.5) { it.cameraCopyMs }} " +
                        "frame_p50_ms=${percentile(.5) { it.timings.totalMs }} " +
                        "frame_p95_ms=${percentile(.95) { it.timings.totalMs }} " +
                        "input_p50_ms=${percentile(.5) { it.timings.inputMs }} " +
                        "inference_p50_ms=${percentile(.5) { it.timings.model.inferenceMs }} " +
                        "render_p50_ms=${percentile(.5) { it.timings.model.renderMs }} " +
                        "output_p50_ms=${percentile(.5) { it.timings.outputMs }} " +
                        "delivery_p50_ms=${percentile(.5) { it.deliveryMs }} " +
                        "gpu_frames=${measured.count { it.analysis.detectorGpu }} " +
                        "face_frames=${measured.count { it.analysis.faces > 0 }} " +
                        "plate_frames=${measured.count { it.analysis.plates > 0 }} " +
                        "protected_frames=${measured.count { it.analysis.maskPixels > 0 }} " +
                        "blur_gpu_frames=${measured.count { it.analysis.maskPixels > 0 && it.analysis.blurGpu }} " +
                        "exempt_frames=${measured.count { it.analysis.exemptFaces > 0 }} errors=${failures.get()}")
                } finally {
                    instrumentation.runOnMainSync {
                        analysis.clearAnalyzer()
                        provider.unbind(preview, analysis)
                        analyzer.stop()
                        renderer.release()
                    }
                    executor.shutdownNow()
                    egl.release()
                }
            }
        }
    }
}
