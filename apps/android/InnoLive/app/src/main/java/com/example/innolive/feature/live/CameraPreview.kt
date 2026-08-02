package com.example.innolive.feature.live

import android.util.Rational
import android.view.Surface
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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

@Composable
fun CameraPreview(
    cameraLensFacing: CameraLensFacing,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
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

    DisposableEffect(context, lifecycleOwner, previewView, cameraLensFacing) {
        hasCameraError = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                    )
                    .build(),
            )
            .build()
            .apply {
                surfaceProvider = previewView.surfaceProvider
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
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            UseCaseGroup.Builder()
                                .addUseCase(preview)
                                .setViewPort(
                                    ViewPort.Builder(
                                        Rational(16, 9),
                                        previewView.display?.rotation ?: Surface.ROTATION_0,
                                    )
                                        .setScaleType(ViewPort.FILL_CENTER)
                                        .build(),
                                )
                                .build(),
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
