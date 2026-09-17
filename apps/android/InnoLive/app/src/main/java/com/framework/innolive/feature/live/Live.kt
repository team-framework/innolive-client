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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
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
import com.framework.innolive.feature.face.FaceManagementScreen
import com.framework.innolive.feature.live.components.PlatformDialog
import com.framework.innolive.feature.live.components.VerticalHeroButton
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog

@Composable
fun LiveScreen(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
) {
    var openFaceManagement by remember { mutableStateOf(false) }
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
        isPreparingBroadcast = webRtcSession.isPreparingBroadcast,
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
    LaunchedEffect(webRtcSession) {
        webRtcSession.restoreAnonymizationSelection(context)
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

    if (openFaceManagement) {
        FaceManagementScreen(
            cameraLensFacing = props.cameraLensFacing,
            onGetAccessToken = props.onGetAccessToken,
            onRefreshAccessToken = props.onRefreshAccessToken,
            onBack = { openFaceManagement = false },
            profileEmail = props.profileEmail,
        )
        return
    }

    val canManageFace =
        webRtcSession.connectionState in setOf(
            WebRtcConnectionState.IDLE,
            WebRtcConnectionState.FAILED,
            WebRtcConnectionState.CONNECTED,
        ) && !webRtcSession.isPreparingBroadcast &&
            webRtcSession.broadcastState in setOf(BroadcastState.IDLE, BroadcastState.FAILED)

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
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "00:00:00",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
            IconButton(
                enabled = props.canSwitchCamera && !presentation.isConnecting,
                onClick = props.onSwitchCamera,
            ) {
                Icon(
                    painter = painterResource(R.drawable.change_camera),
                    contentDescription = "카메라 전환",
                    modifier = Modifier
                        .padding(1.dp)
                        .width(28.dp)
                        .height(28.dp),
                    tint = Color.White,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BroadcastActionControls(
                presentation = presentation,
                onCancelPreparation = webRtcSession::stopBroadcast,
                onBroadcastAction = {
                    when (presentation.broadcastAction) {
                        LiveBroadcastAction.STOP_BROADCAST -> webRtcSession.stopBroadcast()
                        LiveBroadcastAction.GO_LIVE -> webRtcSession.goLive()
                        LiveBroadcastAction.PREPARE_BROADCAST -> openYouTubeSettingsDialog = true
                        LiveBroadcastAction.SELECT_PLATFORM -> openPlatformDialog = true
                    }
                },
                leading = {
                    IconButton(
                        enabled = canManageFace,
                        onClick = {
                            if (webRtcSession.connectionState != WebRtcConnectionState.CONNECTED) {
                                webRtcSession.close()
                            }
                            openFaceManagement = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "얼굴 관리",
                            modifier = Modifier
                                .padding(1.dp)
                                .width(32.dp)
                                .height(32.dp),
                            tint = Color.White,
                        )
                    }
                },
                centerOverlay = {
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
                            hasYouTubeAccount = props.hasYouTubeAccount,
                            youtubeAccountStatus = props.youtubeAccountStatus,
                            isYouTubeReconnectRequired = props.isYouTubeReconnectRequired,
                            isYouTubeAccountActionInProgress = props.isYouTubeAccountActionInProgress,
                            isYouTubeConnectEnabled = props.isYouTubeConnectEnabled,
                            onSettingsChanged = props.onBroadcastSettingsChanged,
                            onConnectYouTube = props.onConnectYouTube,
                            onDismissRequest = { openYouTubeSettingsDialog = false },
                            onPrepare = {
                                if (readMediaPermissionState(context).missingPermissions.isNotEmpty()) {
                                    mediaPermissions.refresh()
                                    mediaPermissionLauncher.launch(
                                        readMediaPermissionState(context).missingPermissions.toTypedArray(),
                                    )
                                } else if (webRtcSession.prepareBroadcast(
                                        context, props.broadcastSettings, props.onRefreshAccessToken,
                                    )) {
                                    openYouTubeSettingsDialog = false
                                }
                            },
                        )
                    }
                },
                trailing = {
                    AnonymizationControls(
                        state = anonymizationControlsState(
                            webRtcSession.connectionState,
                            webRtcSession.anonymizationState,
                            webRtcSession.selectedAnonymizationEnabled,
                            webRtcSession.isAnonymizationSelectionLoaded,
                            webRtcSession.anonymizationChange,
                        ),
                        onSelect = { enabled -> webRtcSession.selectAnonymization(context, enabled) },
                    )
                },
            )
            if (webRtcSession.connectionState == WebRtcConnectionState.FAILED) {
                Text(
                    webRtcSession.connectionStatus,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            webRtcSession.anonymizationChange.errorMessage?.let { error ->
                Text(
                    text = "$error 비식별화 아이콘을 눌러 다시 시도해 주세요.",
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            StableBroadcastFeedback {
                Text(
                    text = presentation.broadcastStatusText,
                    modifier = Modifier.padding(horizontal = 24.dp),
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
}

@Composable
internal fun StableBroadcastFeedback(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.height(76.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
internal fun BroadcastActionControls(
    presentation: LiveScreenPresentation,
    onCancelPreparation: () -> Unit,
    onBroadcastAction: () -> Unit,
    leading: @Composable () -> Unit,
    centerOverlay: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (presentation.isBroadcastPrepared) {
            Button(
                onClick = onCancelPreparation,
                enabled = !presentation.isBroadcastBusy,
            ) {
                Text(text = "방송 준비 취소")
            }
        }
        BalancedLiveControls(
            leading = leading,
            center = {
                Box(contentAlignment = Alignment.Center) {
                    centerOverlay()
                    VerticalHeroButton(
                        text = presentation.broadcastButtonText,
                        enabled = presentation.isBroadcastButtonEnabled,
                        onClick = onBroadcastAction,
                    )
                }
            },
            trailing = trailing,
        )
    }
}

@Composable
internal fun BalancedLiveControls(
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    center: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            leading()
        }
        center()
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            trailing()
        }
    }
}
