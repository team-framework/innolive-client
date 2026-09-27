package com.framework.innolive.feature.live

import android.app.Activity
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.Range
import android.util.Size
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import com.framework.innolive.R
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    cameraLensFacing: CameraLensFacing,
    cameraResolution: CameraResolution?,
    frameAnalyzer: CameraFrameAnalyzer? = null,
    lockedRotation: Int? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var displayRotation by remember(context) {
        mutableIntStateOf((context as? Activity)?.display?.rotation ?: Surface.ROTATION_0)
    }
    DisposableEffect(context) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = (context as? Activity)?.display ?: return
                if (display.displayId == displayId) displayRotation = display.rotation
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }
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
    val targetRotation = lockedRotation ?: displayRotation

    DisposableEffect(
        context,
        lifecycleOwner,
        previewView,
        cameraLensFacing,
        cameraResolution,
        frameAnalyzer,
        targetRotation,
        isLandscape,
    ) {
        frameAnalyzer?.resetFaceExceptions()
        hasCameraError = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val resolutionSelector = cameraResolution?.let { resolution ->
            ResolutionSelector.Builder()
                .setResolutionFilter { supportedSizes, _ ->
                    supportedSizes.filter { size ->
                        isWithinCameraResolutionLimit(size.width, size.height)
                    }
                }
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
            .setTargetRotation(targetRotation)
            .build()
            .apply {
                surfaceProvider = previewView.surfaceProvider
            }
        val analysisExecutor = frameAnalyzer?.let { Executors.newSingleThreadExecutor() }
        val imageAnalysis = frameAnalyzer?.let { analyzer ->
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(targetRotation)
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
                        val viewPort = ViewPort.Builder(
                            if (isLandscape) Rational(16, 9) else Rational(9, 16),
                            targetRotation,
                        )
                            .setScaleType(ViewPort.FILL_CENTER)
                            .build()
                        CameraFrameRateBinding.bind(cameraProvider,lifecycleOwner,cameraSelector,
                            listOfNotNull(preview,imageAnalysis),viewPort)
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
                text = stringResource(R.string.error_camera_preview),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** CameraX's supported range is specific to the combined preview and analysis session. */
internal object CameraFrameRateBinding {
    fun bind(provider:ProcessCameraProvider, owner:LifecycleOwner, selector:CameraSelector,
             useCases:List<UseCase>, viewPort:ViewPort):Boolean {
        val exact30=Range(30,30)
        val proposed=SessionConfig.Builder(useCases).setViewPort(viewPort)
        val supported=runCatching {
            val info=provider.getCameraInfo(selector)
            info.getSupportedFrameRateRanges(proposed.build()).contains(exact30) &&
                info.isSessionConfigSupported(proposed.setFrameRateRange(exact30).build())
        }.getOrDefault(false)
        if(supported) {
            try {
                provider.bindToLifecycle(owner,selector,proposed.build())
                Log.i("PrivacyCamera","capture_range=30-30")
                return true
            } catch(error:Exception) {
                provider.unbind(*useCases.toTypedArray())
                Log.w("PrivacyCamera","capture_range_fallback type=${error.javaClass.simpleName}")
            }
        }
        val group=UseCaseGroup.Builder().apply {useCases.forEach(::addUseCase)}
            .setViewPort(viewPort).build()
        provider.bindToLifecycle(owner,selector,group)
        Log.i("PrivacyCamera","capture_range=default")
        return false
    }
}
