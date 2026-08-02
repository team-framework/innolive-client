package com.example.innolive.feature.live

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    var hasCameraError by remember { mutableStateOf(false) }
    var previewAspectRatio by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(context, lifecycleOwner, previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
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
                        val cameraSelector = when {
                            cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            }

                            cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }

                            else -> error("사용 가능한 카메라가 없습니다.")
                        }

                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                        )

                        preview.resolutionInfo?.let { resolutionInfo ->
                            val resolution = resolutionInfo.resolution
                            previewAspectRatio = if (resolutionInfo.rotationDegrees % 180 == 0) {
                                resolution.width.toFloat() / resolution.height
                            } else {
                                resolution.height.toFloat() / resolution.width
                            }
                        }
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

    BoxWithConstraints(modifier = modifier) {
        val aspectRatio = previewAspectRatio
        val previewModifier = if (aspectRatio == null) {
            Modifier.fillMaxSize()
        } else {
            val previewWidth = minOf(maxWidth, maxHeight * aspectRatio)

            Modifier
                .align(Alignment.Center)
                .width(previewWidth)
                .aspectRatio(aspectRatio)
        }

        AndroidView(
            factory = { previewView },
            modifier = previewModifier,
        )

        if (hasCameraError) {
            Text(
                text = "카메라 화면을 불러오지 못했습니다.",
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
