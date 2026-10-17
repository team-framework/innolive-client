package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.util.Size
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.isWithinCameraResolutionLimit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val TARGET_WIDTH = 1_280
private const val TARGET_HEIGHT = 720
private const val FRAME_INTERVAL_NANOS = 180_000_000L

/**
 * 얼굴 등록 프레임을 공급합니다. [broadcastCamera]가 있으면 방송 카메라를 끄지 않고 그 프레임을 나눠 받고,
 * 없으면 얼굴 등록 전용 카메라를 엽니다.
 */
@Composable
internal fun FaceCaptureFrames(
    broadcastCamera: CameraFrameAnalyzer?,
    cameraLensFacing: CameraLensFacing,
    enabled: Boolean,
    onFrame: (Bitmap) -> Unit,
    onSourceTooSmall: () -> Unit,
    onCameraError: () -> Unit,
) {
    if (broadcastCamera != null) {
        BroadcastCameraFaceFrames(broadcastCamera, enabled, onFrame, onSourceTooSmall, onCameraError)
    } else {
        FaceCaptureCamera(cameraLensFacing, enabled, onFrame, onSourceTooSmall, onCameraError)
    }
}

@Composable
private fun BroadcastCameraFaceFrames(
    analyzer: CameraFrameAnalyzer,
    enabled: Boolean,
    onFrame: (Bitmap) -> Unit,
    onSourceTooSmall: () -> Unit,
    onCameraError: () -> Unit,
) {
    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnSourceTooSmall by rememberUpdatedState(onSourceTooSmall)
    val currentOnCameraError by rememberUpdatedState(onCameraError)

    if (!enabled) return

    DisposableEffect(analyzer) {
        // 회전·자르기·축소는 송출 분석 스레드를 붙잡지 않도록 별도 스레드에서 합니다.
        val conversionExecutor = Executors.newSingleThreadExecutor()
        val isDisposed = AtomicBoolean(false)
        val isConverting = AtomicBoolean(false)
        var nextFrameAtNanos = 0L
        val tap: (ImageProxy) -> Unit = tap@{ image ->
            if (isDisposed.get()) return@tap
            val now = System.nanoTime()
            if (now < nextFrameAtNanos || !isConverting.compareAndSet(false, true)) return@tap
            nextFrameAtNanos = now + FRAME_INTERVAL_NANOS
            val rotation = image.imageInfo.rotationDegrees
            val source = try {
                image.toBitmap()
            } catch (error: Exception) {
                isConverting.set(false)
                Log.e("FaceCaptureCamera", "Broadcast face frame conversion failed", error)
                if (!isDisposed.get()) currentOnCameraError()
                return@tap
            }
            try {
                conversionExecutor.execute {
                    try {
                        val bitmap = source.toReferenceFaceBitmap(rotation)
                        when {
                            bitmap == null -> if (!isDisposed.get()) currentOnSourceTooSmall()
                            isDisposed.get() -> bitmap.recycle()
                            else -> currentOnFrame(bitmap)
                        }
                    } catch (error: Exception) {
                        Log.e("FaceCaptureCamera", "Broadcast face frame conversion failed", error)
                        if (!isDisposed.get()) currentOnCameraError()
                    } finally {
                        isConverting.set(false)
                    }
                }
            } catch (_: RejectedExecutionException) {
                source.recycle()
                isConverting.set(false)
            }
        }
        analyzer.setFaceFrameTap(tap)

        onDispose {
            isDisposed.set(true)
            analyzer.clearFaceFrameTap(tap)
            conversionExecutor.shutdown()
        }
    }
}

@Composable
internal fun FaceCaptureCamera(
    cameraLensFacing: CameraLensFacing,
    enabled: Boolean,
    onFrame: (Bitmap) -> Unit,
    onSourceTooSmall: () -> Unit,
    onCameraError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnSourceTooSmall by rememberUpdatedState(onSourceTooSmall)
    val currentOnCameraError by rememberUpdatedState(onCameraError)

    if (!enabled) return

    DisposableEffect(context, lifecycleOwner, cameraLensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionFilter { supportedSizes, _ ->
                supportedSizes.filter { size ->
                    isWithinCameraResolutionLimit(size.width, size.height)
                }
            }
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(TARGET_WIDTH, TARGET_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolutionSelector)
            .build()
        var cameraProvider: ProcessCameraProvider? = null
        val isDisposed = AtomicBoolean(false)
        var nextFrameAtNanos = 0L

        imageAnalysis.setAnalyzer(
            analysisExecutor,
            object : ImageAnalysis.Analyzer {
                override fun analyze(image: ImageProxy) {
                    try {
                        if (isDisposed.get()) return
                        val now = System.nanoTime()
                        if (now < nextFrameAtNanos) return
                        nextFrameAtNanos = now + FRAME_INTERVAL_NANOS

                        val bitmap = try {
                            image.toReferenceFaceBitmap()
                        } catch (error: Exception) {
                            Log.e("FaceCaptureCamera", "Face capture frame conversion failed", error)
                            if (!isDisposed.get()) currentOnCameraError()
                            return
                        }
                        if (bitmap == null) {
                            if (!isDisposed.get()) currentOnSourceTooSmall()
                        } else if (!isDisposed.get()) {
                            currentOnFrame(bitmap)
                        } else {
                            bitmap.recycle()
                        }
                    } finally {
                        image.close()
                    }
                }
            },
        )

        cameraProviderFuture.addListener(
            {
                if (isDisposed.get()) return@addListener
                try {
                    cameraProvider = cameraProviderFuture.get()
                    val cameraSelector = when (cameraLensFacing) {
                        CameraLensFacing.BACK ->
                            CameraSelector.DEFAULT_BACK_CAMERA

                        CameraLensFacing.FRONT ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    check(cameraProvider?.hasCamera(cameraSelector) == true) {
                        "선택한 카메라를 사용할 수 없습니다."
                    }
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        imageAnalysis,
                    )
                } catch (error: Exception) {
                    Log.e("FaceCaptureCamera", "Face capture camera initialization failed", error)
                    if (!isDisposed.get()) currentOnCameraError()
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            isDisposed.set(true)
            imageAnalysis.clearAnalyzer()
            cameraProvider?.unbind(imageAnalysis)
            // Drain already queued callbacks; each one closes its ImageProxy in finally.
            analysisExecutor.shutdown()
        }
    }
}
