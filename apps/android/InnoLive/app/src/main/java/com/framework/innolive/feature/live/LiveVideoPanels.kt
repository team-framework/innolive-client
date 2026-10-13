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
import androidx.compose.ui.zIndex
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
    onDeviceProcessing: Boolean = false,
    modifier: Modifier = Modifier,
    localVideoTrack: VideoTrack? = null,
    videoQualitySettings: BroadcastVideoQualitySettings = BroadcastVideoQualitySettings(),
    onVideoQualityCaptureStateChanged: (VideoQualityCaptureState) -> Unit = {},
    /** 앱 직접 송출이면 서버에서 돌아오는 영상이 없으므로 카메라를 계속 메인 화면에 둡니다. */
    isDirectPublish: Boolean = false,
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

        val remoteIsMain = isConnected && !isDirectPublish
        if (!isDirectPublish) {
            WebRtcRemotePreview(
                remoteVideoTrack = liveMainVideoTrack(onDeviceProcessing, isConnected, localVideoTrack, remoteVideoTrack),
                eglContext = eglContext,
                cameraLensFacing = cameraLensFacing,
                modifier = (if (remoteIsMain) mainModifier else pipModifier)
                    .zIndex(if (remoteIsMain) 0f else 1f),
            )
        }
        Box(modifier = (if (remoteIsMain) pipModifier else mainModifier)
            .zIndex(if (remoteIsMain) 1f else 0f)) {
            CameraPreview(
                cameraLensFacing = cameraLensFacing,
                cameraResolution = cameraResolution,
                frameAnalyzer = frameAnalyzer,
                lockedRotation = lockedRotation,
                videoQualitySettings = videoQualitySettings,
                onVideoQualityCaptureStateChanged = onVideoQualityCaptureStateChanged,
                showAdjustedColorPreview = localVideoTrack == null,
                // 직접 송출은 가림 처리 출력 텍스처를 그대로 미리 보여 주므로 같은 EGL 컨텍스트를 씁니다.
                processedPreviewEglContext = if (isDirectPublish) eglContext else null,
                modifier = Modifier.fillMaxSize(),
            )
            if (isConnected && !onDeviceProcessing && localVideoTrack != null) {
                WebRtcRemotePreview(
                    remoteVideoTrack = localVideoTrack,
                    eglContext = eglContext,
                    cameraLensFacing = cameraLensFacing,
                    isMediaOverlay = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** A locally protected preview must not inherit server return stalls or stale remote frames. */
internal fun <T> liveMainVideoTrack(onDeviceProcessing: Boolean, isConnected: Boolean,
    local: T?, remote: T?): T? = when {
    !isConnected -> null
    onDeviceProcessing -> local
    else -> remote
}
