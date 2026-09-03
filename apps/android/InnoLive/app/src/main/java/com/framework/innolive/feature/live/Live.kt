package com.framework.innolive.feature.live

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.components.PlatformDialog
import com.framework.innolive.feature.live.components.VerticalHeroButton
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog

@Composable
fun LiveScreen(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
) {
    var openPlatformDialog by remember { mutableStateOf(false) }
    var openYouTubeSettingsDialog by remember { mutableStateOf(false) }
    var pendingYouTubeSettingsDialog by remember { mutableStateOf(false) }
    var selectedPlatform by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val presentation = buildLiveScreenPresentation(
        connectionState = webRtcSession.connectionState,
        broadcastState = webRtcSession.broadcastState,
        selectedPlatform = selectedPlatform,
        broadcastStatus = webRtcSession.broadcastStatus,
    )
    val mediaPermissions = rememberMediaPermissionController(context)
    val mediaPermissionState = mediaPermissions.state
    val missingMediaPermissions = mediaPermissionState.missingPermissions
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { mediaPermissions.refresh() },
    )
    val requestMissingMediaPermissions = {
        if (missingMediaPermissions.isNotEmpty()) {
            mediaPermissionLauncher.launch(missingMediaPermissions.toTypedArray())
        }
    }
    LaunchedEffect(Unit) {
        requestMissingMediaPermissions()
    }
    LaunchedEffect(openPlatformDialog, pendingYouTubeSettingsDialog) {
        if (!openPlatformDialog && pendingYouTubeSettingsDialog) {
            pendingYouTubeSettingsDialog = false
            openYouTubeSettingsDialog = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.Black),
    ) {
        if (mediaPermissionState.hasCameraPermission) {
            LiveVideoPanels(
                cameraLensFacing = props.cameraLensFacing,
                cameraResolution = props.cameraResolution,
                frameAnalyzer = webRtcSession.frameAnalyzer,
                remoteVideoTrack = webRtcSession.remoteVideoTrack,
                eglContext = webRtcSession.eglContext,
                isConnected = presentation.isConnected,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "카메라와 마이크 권한이 필요합니다.")
                Button(
                    onClick = requestMissingMediaPermissions,
                ) {
                    Text(text = "권한 허용")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = props.onOpenSettings,
                enabled = !presentation.isConnecting,
            ) {
                Icon(
                    painter = painterResource(R.drawable.settings),
                    contentDescription = "settings",
                    modifier = Modifier
                        .padding(1.dp)
                        .width(28.dp)
                        .height(28.dp),
                    tint = Color.White
                )
            }
            Text(
                text = "00:00:00",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp, 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {}) {
                    Icon(
                        modifier = Modifier
                            .padding(1.dp)
                            .width(32.dp)
                            .height(32.dp),
                        painter = painterResource(R.drawable.change_camera),
                        contentDescription = "Change camera facing",
                        tint = Color.White
                    )
                }
                Box(contentAlignment = Alignment.Center) {
                    if (openPlatformDialog) {
                        PlatformDialog(
                            onDismissRequest = {
                                openPlatformDialog = false
                            },
                            onYouTubeSelected = {
                                selectedPlatform = "YouTube"
                                pendingYouTubeSettingsDialog = true
                                openPlatformDialog = false
                            },
                        )
                    }
                    if (openYouTubeSettingsDialog) {
                        YouTubeLiveSettingsDialog(
                            settings = props.broadcastSettings,
                            youtubeChannelTitle = props.youtubeChannelTitle,
                            youtubeAccountStatus = props.youtubeAccountStatus,
                            isYouTubeReconnectRequired = props.isYouTubeReconnectRequired,
                            isYouTubeAccountActionInProgress = props.isYouTubeAccountActionInProgress,
                            isYouTubeConnectEnabled = props.isYouTubeConnectEnabled,
                            onSettingsChanged = props.onBroadcastSettingsChanged,
                            onConnectYouTube = props.onConnectYouTube,
                            onDismissRequest = { openYouTubeSettingsDialog = false },
                        )
                    }
                    VerticalHeroButton(
                        text = presentation.broadcastButtonText,
                        enabled = presentation.isBroadcastButtonEnabled,
                        onClick = {
                            when (presentation.broadcastAction) {
                                LiveBroadcastAction.STOP_BROADCAST -> webRtcSession.stopBroadcast()
                                LiveBroadcastAction.GO_LIVE -> webRtcSession.goLive()
                                LiveBroadcastAction.PREPARE_BROADCAST ->
                                    webRtcSession.prepareBroadcast(props.broadcastSettings)

                                LiveBroadcastAction.SELECT_PLATFORM -> openPlatformDialog = true
                            }
                        },
                    )
                }
                IconButton(
                    enabled = !presentation.isConnecting,
                    onClick = {
                        if (presentation.isConnected) {
                            webRtcSession.close()
                        } else if (missingMediaPermissions.isNotEmpty()) {
                            requestMissingMediaPermissions()
                        } else {
                            webRtcSession.start(context, props.onRefreshAccessToken)
                        }
                    }) {
                    Icon(
                        modifier = Modifier
                            .padding(1.dp)
                            .width(32.dp)
                            .height(32.dp),
                        painter = painterResource(
                            if (presentation.isConnected) R.drawable.blur_enabled else R.drawable.blur_disabled,
                        ),
                        contentDescription = "Toggle face blur",
                        tint = Color.White
                    )
                }
            }
            if (presentation.isBroadcastPrepared) {
                Button(
                    onClick = webRtcSession::stopBroadcast,
                    enabled = !presentation.isBroadcastBusy,
                ) {
                    Text(text = "방송 준비 취소")
                }
            }
            Text(
                text = presentation.broadcastStatusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (presentation.isBroadcastStatusError) {
                    Color.Red
                } else {
                    Color.White
                },
            )
        }
    }
}
