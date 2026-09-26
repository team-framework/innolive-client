package com.framework.innolive.feature.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.webrtc.EglBase
import org.webrtc.VideoTrack

@Composable
fun LiveVideoPanels(
    cameraLensFacing: CameraLensFacing,
    cameraResolution: CameraResolution?,
    frameAnalyzer: CameraFrameAnalyzer?,
    lockedRotation: Int?,
    remoteVideoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    localVideoTrack: VideoTrack? = null,
    videoQualitySettings: BroadcastVideoQualitySettings = BroadcastVideoQualitySettings(),
    onVideoQualityCaptureStateChanged: (VideoQualityCaptureState) -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier) {
        val aspectRatio = if (maxWidth > maxHeight) 16f / 9f else 9f / 16f
        val mainWidth = minOf(maxWidth, maxHeight * aspectRatio)
        val mainHeight = mainWidth / aspectRatio
        val pipWidth = minOf(maxWidth * 0.3f, maxHeight * 0.42f * aspectRatio)
        val pipHeight = pipWidth / aspectRatio
        val mainModifier = Modifier
            .align(Alignment.Center)
            .size(mainWidth, mainHeight)
        val pipModifier = Modifier
            .align(Alignment.TopStart)
            .offset(y = 50.dp)
            .padding(12.dp)
            .size(pipWidth, pipHeight)
            .clip(shape = RoundedCornerShape(8.dp))

        if (isConnected) {
            WebRtcRemotePreview(
                remoteVideoTrack = remoteVideoTrack,
                eglContext = eglContext,
                cameraLensFacing = cameraLensFacing,
                modifier = mainModifier,
            )
            Box(modifier = pipModifier) {
                // Keep CameraX bound below the processed local track: it owns capture for WebRTC.
                CameraPreview(
                    cameraLensFacing = cameraLensFacing,
                    cameraResolution = cameraResolution,
                    frameAnalyzer = frameAnalyzer,
                    lockedRotation = lockedRotation,
                    videoQualitySettings = videoQualitySettings,
                    onVideoQualityCaptureStateChanged = onVideoQualityCaptureStateChanged,
                    modifier = Modifier.fillMaxSize(),
                )
                WebRtcRemotePreview(
                    remoteVideoTrack = localVideoTrack,
                    eglContext = eglContext,
                    cameraLensFacing = cameraLensFacing,
                    isMediaOverlay = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            CameraPreview(
                cameraLensFacing = cameraLensFacing,
                cameraResolution = cameraResolution,
                frameAnalyzer = frameAnalyzer,
                lockedRotation = lockedRotation,
                videoQualitySettings = videoQualitySettings,
                onVideoQualityCaptureStateChanged = onVideoQualityCaptureStateChanged,
                modifier = mainModifier,
            )
            WebRtcRemotePreview(
                remoteVideoTrack = null,
                eglContext = eglContext,
                cameraLensFacing = cameraLensFacing,
                modifier = pipModifier,
            )
        }
    }
}
