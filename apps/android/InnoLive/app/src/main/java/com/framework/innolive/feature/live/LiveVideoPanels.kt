package com.framework.innolive.feature.live

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.webrtc.EglBase
import org.webrtc.VideoTrack

@Composable
fun LiveVideoPanels(
    cameraLensFacing: CameraLensFacing,
    cameraResolution: CameraResolution?,
    frameAnalyzer: CameraFrameAnalyzer?,
    remoteVideoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        var backdropBitmap by remember { mutableStateOf<Bitmap?>(null) }
        val aspectRatio = 9f / 16f
        val mainWidth = maxWidth
        val mainHeight = mainWidth / aspectRatio
        val pipWidth = maxWidth * 0.3f
        val pipHeight = pipWidth / aspectRatio
        val mainModifier = Modifier
            .align(Alignment.Center)
            .size(mainWidth, mainHeight)
        val pipModifier = Modifier
            .align(Alignment.TopStart)
            .padding(12.dp)
            .size(pipWidth, pipHeight)

        if (isConnected) {
            WebRtcRemotePreview(
                remoteVideoTrack = remoteVideoTrack,
                eglContext = eglContext,
                modifier = mainModifier,
            )
            CameraPreview(
                cameraLensFacing = cameraLensFacing,
                cameraResolution = cameraResolution,
                frameAnalyzer = frameAnalyzer,
                modifier = pipModifier,
            )
        } else {
            backdropBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(32.dp),
                )
            }
            CameraPreview(
                cameraLensFacing = cameraLensFacing,
                cameraResolution = cameraResolution,
                frameAnalyzer = frameAnalyzer,
                onPreviewBitmap = { bitmap -> backdropBitmap = bitmap },
                modifier = mainModifier,
            )
            WebRtcRemotePreview(
                remoteVideoTrack = null,
                eglContext = eglContext,
                modifier = pipModifier,
            )
        }
    }
}
