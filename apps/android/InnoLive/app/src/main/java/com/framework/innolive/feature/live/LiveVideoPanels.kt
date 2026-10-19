package com.framework.innolive.feature.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
    /** 얼굴 선택처럼 가림 처리 전 원본을 크게 봐야 할 때 원본 카메라를 메인에, 송출 화면을 작은 창에 둡니다. */
    originalIsMain: Boolean = false,
    /** 메인 영역과 같은 크기로 그 위에 그릴 내용입니다. 얼굴 선택 윤곽이 메인 영상 좌표에 맞도록 씁니다. */
    mainOverlay: (@Composable BoxScope.() -> Unit)? = null,
    /** 원본 카메라가 열릴 때마다 그 카메라 방향으로 불립니다. */
    onCameraOpened: (CameraLensFacing) -> Unit = {},
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val aspectRatio = if (maxWidth > maxHeight) 16f / 9f else 9f / 16f
        val mainWidth = minOf(maxWidth, maxHeight * aspectRatio)
        val mainHeight = mainWidth / aspectRatio
        // 가로 화면은 높이가 낮아 같은 비율이면 작은 창이 화면을 많이 가리므로 조금 더 작게 둡니다.
        val pipHeightRatio = if (maxWidth > maxHeight) 0.34f else 0.42f
        val pipWidth = minOf(maxWidth * 0.3f, maxHeight * pipHeightRatio * aspectRatio)
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
        // 가림 처리된 송출 영상은 연결 뒤에만 있습니다. 얼굴 선택처럼 원본을 크게 봐야 할 때는 송출 영상을 작은 창으로 보냅니다.
        val hasProcessedVideo = if (isDirectPublish) directProcessedMain else isConnected
        val remoteIsMain = hasProcessedVideo && !originalIsMain
        // 연결 전에는 작은 창에 그릴 영상이 없으므로 빈 검은 창을 띄우지 않습니다.
        val pipVisible = hasProcessedVideo
        val pipArea = with(density) {
            val size = Size(pipWidth.toPx(), pipHeight.toPx())
            val margin = PipMargin.toPx()
            val minY = maxOf((LiveStudioTopInset + PipTopGap).toPx(), pipState.topReservePx + PipTopGap.toPx())
            // 가로 화면에서는 왼쪽 열의 카메라 도구 오른쪽부터 둡니다.
            val minX = margin + pipState.leftControlsWidthPx
            LivePreviewPipArea(
                size = size,
                minX = minX,
                // 가로 화면에서는 오른쪽 열의 방송 버튼과 겹치지 않게 그 왼쪽까지만 둡니다.
                maxX = (constraints.maxWidth - size.width - margin - pipState.rightControlsWidthPx)
                    .coerceAtLeast(minX),
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
                    modifier = (if (remoteIsMain) mainModifier else pipModifier)
                        .zIndex(if (remoteIsMain) 0f else 1f),
                    // 가림 처리 출력 텍스처를 그리려면 분석기와 같은 EGL 컨텍스트를 공유해야 합니다.
                    sharedEglContext = eglContext,
                )
            }
        } else if (hasProcessedVideo) {
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

private val PipMargin = 12.dp
private val PipTopGap = 8.dp

/** A locally protected preview must not inherit server return stalls or stale remote frames. */
internal fun <T> liveMainVideoTrack(onDeviceProcessing: Boolean, isConnected: Boolean,
    local: T?, remote: T?): T? = when {
    !isConnected -> null
    onDeviceProcessing -> local
    else -> remote
}
