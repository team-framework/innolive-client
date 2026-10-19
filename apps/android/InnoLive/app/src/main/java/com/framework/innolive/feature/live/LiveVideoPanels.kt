package com.framework.innolive.feature.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.round
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
    /** 작은 미리보기 창 위치입니다. 없으면 상단 바 아래 왼쪽에 고정합니다. */
    pipState: LivePreviewPipState = rememberLivePreviewPipState(),
) {
    val density = LocalDensity.current
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
            .offset { pipState.offset.round() }
            .size(pipWidth, pipHeight)
            .clip(shape = RoundedCornerShape(8.dp))

        // 직접 송출도 서버 송출처럼 연결 뒤에는 가림 처리 영상을 메인에, 카메라 원본을 작은 창에 둡니다.
        val directProcessedMain = isDirectPublish && isConnected && frameAnalyzer != null && eglContext != null
        val remoteIsMain = if (isDirectPublish) directProcessedMain else isConnected
        // 직접 송출의 연결 전에는 작은 창에 그릴 영상이 없습니다.
        val pipVisible = !isDirectPublish || directProcessedMain
        val pipArea = with(density) {
            val size = Size(pipWidth.toPx(), pipHeight.toPx())
            val margin = PipMargin.toPx()
            val minY = (LiveStudioTopInset + PipTopGap).toPx()
            LivePreviewPipArea(
                size = size,
                minX = margin,
                maxX = (constraints.maxWidth - size.width - margin).coerceAtLeast(margin),
                minY = minY,
                // 기본 화면 하단의 방송 버튼과 상태 패널은 가리지 않는 높이까지 내립니다.
                maxY = (constraints.maxHeight - size.height - pipState.bottomReservePx(this) - margin)
                    .coerceAtLeast(minY),
            )
        }
        SideEffect {
            pipState.area = pipArea
            pipState.isVisible = pipVisible
        }
        if (isDirectPublish) {
            if (directProcessedMain) {
                CameraProcessedPreview(
                    analyzer = checkNotNull(frameAnalyzer),
                    cameraLensFacing = cameraLensFacing,
                    modifier = mainModifier.zIndex(0f),
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

private val PipMargin = 12.dp
private val PipTopGap = 8.dp

/** A locally protected preview must not inherit server return stalls or stale remote frames. */
internal fun <T> liveMainVideoTrack(onDeviceProcessing: Boolean, isConnected: Boolean,
    local: T?, remote: T?): T? = when {
    !isConnected -> null
    onDeviceProcessing -> local
    else -> remote
}
