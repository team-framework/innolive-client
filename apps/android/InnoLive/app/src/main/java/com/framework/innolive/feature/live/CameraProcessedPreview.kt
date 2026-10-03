package com.framework.innolive.feature.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Displays exactly the processed I420 frame used by the sender, without an RGB approximation. */
@Composable
internal fun CameraProcessedPreview(
    analyzer: CameraFrameAnalyzer,
    cameraLensFacing: CameraLensFacing,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val eglBase = remember(context, analyzer) {
        WebRtcNativeRuntime.initialize(context)
        EglBase.create()
    }
    val renderer = remember(context, eglBase) {
        SurfaceViewRenderer(context).apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setMirror(cameraLensFacing.shouldMirrorPreview)
        }
    }
    DisposableEffect(renderer, analyzer, eglBase) {
        analyzer.setProcessedPreviewSink(renderer)
        onDispose {
            analyzer.setProcessedPreviewSink(null)
            renderer.release()
            eglBase.release()
        }
    }
    AndroidView(
        factory = { renderer },
        update = { it.setMirror(cameraLensFacing.shouldMirrorPreview) },
        modifier = modifier,
    )
}
