package com.framework.innolive.feature.live

import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    cameraLensFacing: CameraLensFacing,
    cameraResolution: CameraResolution?,
    frameAnalyzer: CameraFrameAnalyzer? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val previewContainer = remember(context, previewView) {
        FrameLayout(context).apply {
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            addView(
                previewView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }
    var hasCameraError by remember { mutableStateOf(false) }

    DisposableEffect(
        context,
        lifecycleOwner,
        previewView,
        cameraLensFacing,
        cameraResolution,
        frameAnalyzer,
    ) {
        hasCameraError = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val resolutionSelector = cameraResolution?.let { resolution ->
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(resolution.width, resolution.height),
                        ResolutionStrategy.FALLBACK_RULE_NONE,
                    ),
                )
                .build()
        }
        val preview = Preview.Builder()
            .apply {
                resolutionSelector?.let(::setResolutionSelector)
            }
            .build()
            .apply {
                surfaceProvider = previewView.surfaceProvider
            }
        val analysisExecutor = frameAnalyzer?.let { Executors.newSingleThreadExecutor() }
        val imageAnalysis = frameAnalyzer?.let { analyzer ->
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .apply {
                    resolutionSelector?.let(::setResolutionSelector)
                }
                .build()
                .apply {
                    setAnalyzer(checkNotNull(analysisExecutor), analyzer)
                }
        }
        var cameraProvider: ProcessCameraProvider? = null
        var isDisposed = false

        cameraProviderFuture.addListener(
            {
                if (!isDisposed) {
                    try {
                        cameraProvider = cameraProviderFuture.get()
                        val cameraSelector = when (cameraLensFacing) {
                            CameraLensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                            CameraLensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                        }

                        check(cameraProvider.hasCamera(cameraSelector)) {
                            "선택한 카메라를 사용할 수 없습니다."
                        }
                        val useCaseGroup = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .apply {
                                imageAnalysis?.let(::addUseCase)
                            }
                            .setViewPort(
                                ViewPort.Builder(
                                    Rational(9, 16),
                                    previewView.display?.rotation ?: Surface.ROTATION_0,
                                )
                                    .setScaleType(ViewPort.FILL_CENTER)
                                    .build(),
                            )
                            .build()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            useCaseGroup,
                        )
                    } catch (_: Exception) {
                        hasCameraError = true
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            isDisposed = true
            cameraProvider?.unbind(preview)
            imageAnalysis?.let { analysis ->
                analysis.clearAnalyzer()
                cameraProvider?.unbind(analysis)
            }
            analysisExecutor?.shutdownNow()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewContainer },
            modifier = Modifier.fillMaxSize(),
        )

        if (hasCameraError) {
            Text(
                text = "카메라 화면을 불러오지 못했습니다.",
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
