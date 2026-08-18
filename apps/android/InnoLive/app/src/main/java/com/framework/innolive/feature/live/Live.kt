package com.framework.innolive.feature.live

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

@Composable
fun LiveScreen(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
) {
    val context = LocalContext.current
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

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (hasCameraPermission) {
            LiveVideoPanels(
                cameraLensFacing = props.cameraLensFacing,
                cameraResolution = props.cameraResolution,
                frameAnalyzer = webRtcSession.connection?.frameAnalyzer,
                remoteVideoTrack = webRtcSession.remoteVideoTrack,
                eglContext = webRtcSession.connection?.eglContext,
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
                Text(text = "카메라와 마이크 권한이 필요합니다.")
                Button(
                    onClick = requestMissingMediaPermissions,
                ) {
                    Text(text = "권한 허용")
                }
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
            Text(text = webRtcSession.connectionStatus)
            Button(
                enabled = webRtcSession.connectionState == WebRtcConnectionState.IDLE ||
                    webRtcSession.connectionState == WebRtcConnectionState.CONNECTED,
                onClick = {
                    if (webRtcSession.connectionState == WebRtcConnectionState.CONNECTED) {
                        webRtcSession.close()
                    } else if (!hasCameraPermission || !hasMicrophonePermission) {
                        requestMissingMediaPermissions()
                    } else {
                        webRtcSession.start(context, props.onRefreshAccessToken)
                    }
                },
            ) {
                Text(
                    text = when {
                        webRtcSession.connectionState == WebRtcConnectionState.CONNECTED -> {
                            "WebRTC 연결 종료"
                        }

                        !hasCameraPermission || !hasMicrophonePermission -> {
                            "카메라 및 마이크 권한 허용"
                        }

                        else -> "WebRTC 연결 시작"
                    },
                )
            }
        }
    }
}
