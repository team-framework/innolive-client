package com.framework.innolive.feature.live

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        val isPortrait = maxHeight >= maxWidth
        val aspectRatio = if (isPortrait) 9f / 16f else 16f / 9f
        val mainWidth = minOf(maxWidth, maxHeight * aspectRatio)
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
            CameraPreview(
                cameraLensFacing = cameraLensFacing,
                cameraResolution = cameraResolution,
                frameAnalyzer = frameAnalyzer,
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
