package com.framework.innolive.feature.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val spacing = 12.dp
        val isPortrait = maxHeight >= maxWidth

        if (isPortrait) {
            val panelWidth = minOf(maxWidth, (maxHeight - spacing) / 2 * 16f / 9f)
            val panelHeight = panelWidth * 9f / 16f
            Column(
                modifier = Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                CameraPreview(
                    cameraLensFacing = cameraLensFacing,
                    cameraResolution = cameraResolution,
                    frameAnalyzer = frameAnalyzer,
                    modifier = Modifier.size(panelWidth, panelHeight),
                )
                WebRtcRemotePreview(
                    remoteVideoTrack = remoteVideoTrack,
                    eglContext = eglContext,
                    modifier = Modifier.size(panelWidth, panelHeight),
                )
            }
        } else {
            val panelWidth = minOf((maxWidth - spacing) / 2, maxHeight * 16f / 9f)
            val panelHeight = panelWidth * 9f / 16f
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                CameraPreview(
                    cameraLensFacing = cameraLensFacing,
                    cameraResolution = cameraResolution,
                    frameAnalyzer = frameAnalyzer,
                    modifier = Modifier.size(panelWidth, panelHeight),
                )
                WebRtcRemotePreview(
                    remoteVideoTrack = remoteVideoTrack,
                    eglContext = eglContext,
                    modifier = Modifier.size(panelWidth, panelHeight),
                )
            }
        }
    }
}
