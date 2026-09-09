package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.util.Size
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
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.isWithinCameraResolutionLimit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

private const val TARGET_WIDTH = 1_280
private const val TARGET_HEIGHT = 720
private const val FRAME_INTERVAL_NANOS = 180_000_000L

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
                        val now = System.nanoTime()
                        if (now < nextFrameAtNanos) return
                        nextFrameAtNanos = now + FRAME_INTERVAL_NANOS

                        val bitmap = try {
                            image.toReferenceFaceBitmap()
                        } catch (_: Exception) {
                            if (!isDisposed.get()) currentOnCameraError()
                            return
                        }
                        if (bitmap == null) {
                            if (!isDisposed.get()) currentOnSourceTooSmall()
                        } else if (!isDisposed.get()) {
                            currentOnFrame(bitmap)
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
                } catch (_: Exception) {
                    if (!isDisposed.get()) currentOnCameraError()
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            isDisposed.set(true)
            cameraProvider?.unbind(imageAnalysis)
            imageAnalysis.clearAnalyzer()
            analysisExecutor.shutdownNow()
        }
    }
}
