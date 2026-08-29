package com.framework.innolive.feature.live

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.framework.innolive.R
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
import androidx.core.content.ContextCompat
import com.framework.innolive.feature.live.components.PlatformDialog
import com.framework.innolive.feature.live.components.VerticalHeroButton
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog

private val TAG = "Live Screen"

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
    val isConnected =
        webRtcSession.connectionState == WebRtcConnectionState.CONNECTED
    val isConnecting =
        webRtcSession.connectionState == WebRtcConnectionState.CONNECTING
    val isBroadcastLive = webRtcSession.broadcastState == BroadcastState.LIVE
    val isBroadcastPrepared = webRtcSession.broadcastState == BroadcastState.PREPARED
    val isBroadcastBusy = webRtcSession.broadcastState in setOf(
        BroadcastState.SAVING_SETTINGS,
        BroadcastState.PREPARING,
        BroadcastState.GOING_LIVE,
        BroadcastState.STOPPING,
    )
    val broadcastButtonText = when {
        isBroadcastLive -> "방송 종료"
        isBroadcastPrepared -> "라이브 시작"
        isBroadcastBusy -> "방송 준비 중"
        else -> "방송 준비"
    }
    val isYoutubeValidated by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasMicrophonePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            hasCameraPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            hasMicrophonePermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        },
    )
    val requestMissingMediaPermissions = {
        val missingPermissions = buildList {
            if (!hasCameraPermission) add(Manifest.permission.CAMERA)
            if (!hasMicrophonePermission) add(Manifest.permission.RECORD_AUDIO)
        }
        if (missingPermissions.isNotEmpty()) {
            mediaPermissionLauncher.launch(missingPermissions.toTypedArray())
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
        if (hasCameraPermission) {
            LiveVideoPanels(
                cameraLensFacing = props.cameraLensFacing,
                cameraResolution = props.cameraResolution,
                frameAnalyzer = webRtcSession.connection?.frameAnalyzer,
                remoteVideoTrack = webRtcSession.remoteVideoTrack,
                eglContext = webRtcSession.connection?.eglContext,
                isConnected = webRtcSession.connectionState == WebRtcConnectionState.CONNECTED,
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
                enabled = !isConnecting
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
                IconButton(onClick = { Log.d(TAG, "카메라 전환") }) {
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
                        text = broadcastButtonText,
                        enabled = isConnected && !isBroadcastBusy,
                        onClick = {
                            if (isBroadcastLive) {
                                webRtcSession.stopBroadcast()
                            } else if (isBroadcastPrepared) {
                                webRtcSession.goLive()
                            } else if (selectedPlatform == "YouTube") {
                                webRtcSession.prepareBroadcast(props.broadcastSettings)
                            } else {
                                openPlatformDialog = true
                            }
                        },
                    )
                }
                IconButton(
                    enabled = !isConnecting,
                    onClick = {
                        Log.d(TAG, "blur clicked")
                        if (isConnected) {
                            webRtcSession.close()
                        } else if (!hasCameraPermission || !hasMicrophonePermission) {
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
                        painter = painterResource(if (isConnected) R.drawable.blur_enabled else R.drawable.blur_disabled),
                        contentDescription = "Toggle face blur",
                        tint = Color.White
                    )
                }
            }
            if (isBroadcastPrepared) {
                Button(
                    onClick = webRtcSession::stopBroadcast,
                    enabled = !isBroadcastBusy,
                ) {
                    Text(text = "방송 준비 취소")
                }
            }
            Text(
                text = when {
                    webRtcSession.connectionState == WebRtcConnectionState.FAILED ->
                        "비식별화 연결에 실패하였습니다."

                    webRtcSession.broadcastState != BroadcastState.IDLE ->
                        webRtcSession.broadcastStatus

                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (
                    webRtcSession.connectionState == WebRtcConnectionState.FAILED ||
                    webRtcSession.broadcastState == BroadcastState.FAILED
                ) {
                    Color.Red
                } else {
                    Color.White
                },
            )
        }
    }
}
