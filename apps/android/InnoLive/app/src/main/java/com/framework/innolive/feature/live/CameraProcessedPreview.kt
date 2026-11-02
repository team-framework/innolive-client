package com.framework.innolive.feature.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** RGB 근사 없이 송출에 쓰는 처리된 I420 프레임을 그대로 표시합니다. */
@Composable
internal fun CameraProcessedPreview(
    analyzer: CameraFrameAnalyzer,
    cameraLensFacing: CameraLensFacing,
    modifier: Modifier = Modifier,
    /**
     * 가림 처리 출력이 GPU 텍스처일 때 그 텍스처를 만든 EGL 컨텍스트입니다. 공유하지 않으면 텍스처를
     * 그릴 수 없어 미리보기가 멈춥니다(앱 직접 송출).
     */
    sharedEglContext: EglBase.Context? = null,
) {
    val context = LocalContext.current
    val eglBase = remember(context, analyzer, sharedEglContext) {
        WebRtcNativeRuntime.initialize(context)
        if (sharedEglContext != null) EglBase.create(sharedEglContext, EglBase.CONFIG_PLAIN) else EglBase.create()
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
    // AndroidView는 factory를 한 번만 호출하므로, EGL 컨텍스트가 바뀌어 새 renderer를 만들면 view도 새로 붙입니다.
    key(renderer) {
        AndroidView(
            factory = { renderer },
            update = { it.setMirror(cameraLensFacing.shouldMirrorPreview) },
            modifier = modifier,
        )
    }
}
