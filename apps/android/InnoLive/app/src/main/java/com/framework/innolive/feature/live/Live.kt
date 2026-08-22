package com.framework.innolive.feature.live

import android.Manifest
import android.content.pm.PackageManager
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

@Composable
fun LiveScreen(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
) {
    val context = LocalContext.current
    val isConnected by remember { mutableStateOf(webRtcSession.connectionState == WebRtcConnectionState.CONNECTED) }
    val isConnecting by remember { mutableStateOf(webRtcSession.connectionState != WebRtcConnectionState.CONNECTING) }
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (isConnected) {
                        webRtcSession.close()
                    } else if (!hasCameraPermission || !hasMicrophonePermission) {
                        requestMissingMediaPermissions()
                    } else {
                        webRtcSession.start(context, props.onRefreshAccessToken)
                    }
                }) {
                    Icon(
                        painter = painterResource(if (isConnected) R.drawable.blur_enabled else R.drawable.blur_disabled),
                        contentDescription = "toggle face blur"
                    )
                }
            }
            Text(
                text = webRtcSession.connectionStatus,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
