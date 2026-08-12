package com.example.innolive.feature.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.innolive.BuildConfig
import org.webrtc.VideoTrack

@Composable
fun LiveScreen(props: LiveScreenProps) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasCameraPermission = isGranted },
    )
    var connectionState by remember { mutableStateOf(WebRtcConnectionState.IDLE) }
    var connectionStatus by remember { mutableStateOf("WebRTC 연결 대기") }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var webRtcConnection by remember { mutableStateOf<WebRtcConnection?>(null) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(Unit) {
        onDispose { webRtcConnection?.close() }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (hasCameraPermission) {
            LiveVideoPanels(
                cameraLensFacing = props.cameraLensFacing,
                cameraResolution = props.cameraResolution,
                frameAnalyzer = webRtcConnection?.frameAnalyzer,
                remoteVideoTrack = remoteVideoTrack,
                eglContext = webRtcConnection?.eglContext,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 12.dp,
                        top = 112.dp,
                        end = 12.dp,
                        bottom = 88.dp,
                    ),
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "카메라 미리보기를 위해 권한이 필요합니다.")
                Button(
                    onClick = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                ) {
                    Text(text = "카메라 권한 허용")
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileDisplay(
                name = props.profileName,
                email = props.profileEmail,
            )
            OutlinedButton(onClick = props.onLogout) {
                Text(text = "로그아웃")
            }
        }

        Button(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            onClick = props.onOpenSettings,
        ) {
            Text(text = "설정으로 이동")
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = connectionStatus)
            Button(
                enabled = hasCameraPermission && connectionState == WebRtcConnectionState.IDLE,
                onClick = {
                    runCatching {
                        WebRtcConnection(
                            context = context,
                            serverUrl = BuildConfig.INNOLIVE_SERVER_URL,
                            accessToken = props.accessToken,
                            onStateChanged = { state, message ->
                                connectionState = state
                                connectionStatus = message
                            },
                            onRemoteTrackChanged = { track ->
                                remoteVideoTrack = track
                            },
                        ).also { connection ->
                            webRtcConnection = connection
                            connection.start()
                        }
                    }.onFailure { exception ->
                        connectionState = WebRtcConnectionState.FAILED
                        connectionStatus = exception.message ?: "WebRTC 연결을 시작하지 못했습니다."
                    }
                },
            ) {
                Text(text = "WebRTC 연결 시작")
            }
        }
    }
}
