package com.framework.innolive.feature.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
    /** 얼굴 선택처럼 가림 처리 전 원본을 크게 봐야 할 때 원본 카메라를 메인에, 송출 화면을 작은 창에 둡니다. */
    originalIsMain: Boolean = false,
    /** 메인 영역과 같은 크기로 그 위에 그릴 내용입니다. 얼굴 선택 윤곽이 메인 영상 좌표에 맞도록 씁니다. */
    mainOverlay: (@Composable BoxScope.() -> Unit)? = null,
    /** 원본 카메라가 열릴 때마다 그 카메라 방향으로 불립니다. */
    onCameraOpened: (CameraLensFacing) -> Unit = {},
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

        // 직접 송출도 서버 송출처럼 연결 뒤에는 가림 처리 영상을 메인에, 카메라 원본을 작은 창에 둡니다.
        val directProcessedMain = isDirectPublish && isConnected && frameAnalyzer != null && eglContext != null
        val remoteIsMain = (if (isDirectPublish) directProcessedMain else isConnected) && !originalIsMain
        if (isDirectPublish) {
            if (directProcessedMain) {
                CameraProcessedPreview(
                    analyzer = checkNotNull(frameAnalyzer),
                    cameraLensFacing = cameraLensFacing,
                    modifier = (if (remoteIsMain) mainModifier else pipModifier)
                        .zIndex(if (remoteIsMain) 0f else 1f),
                    // 가림 처리 출력 텍스처를 그리려면 분석기와 같은 EGL 컨텍스트를 공유해야 합니다.
                    sharedEglContext = eglContext,
                )
            }
        } else {
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
                // 분석기의 가림 처리 미리보기 출력은 하나뿐이므로 메인이 쓰는 동안 작은 창은 원본만 보여 줍니다.
                showAdjustedColorPreview = if (isDirectPublish) !directProcessedMain else localVideoTrack == null,
                processedPreviewEglContext = if (isDirectPublish) eglContext else null,
                onCameraOpened = onCameraOpened,
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
        if (mainOverlay != null) {
            Box(modifier = mainModifier.zIndex(2f)) { mainOverlay() }
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
