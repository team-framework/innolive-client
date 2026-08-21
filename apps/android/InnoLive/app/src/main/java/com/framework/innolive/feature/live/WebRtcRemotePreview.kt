package com.framework.innolive.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun WebRtcRemotePreview(
    remoteVideoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black),
    ) {
        if (remoteVideoTrack == null || eglContext == null) {
            return@Box
        }

        val context = LocalContext.current
        val renderer = remember(context, eglContext) {
            SurfaceViewRenderer(context).apply {
                init(eglContext, null)
                setEnableHardwareScaler(true)
                setMirror(false)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        }

        DisposableEffect(renderer, remoteVideoTrack) {
            remoteVideoTrack.addSink(renderer)
            onDispose {
                remoteVideoTrack.removeSink(renderer)
            }
        }
        DisposableEffect(renderer) {
            onDispose { renderer.release() }
        }

        AndroidView(
            factory = { renderer },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
