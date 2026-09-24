package com.framework.innolive.feature.live

import android.app.Activity
import android.os.SystemClock
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.R
import com.framework.innolive.feature.face.FaceManagementScreen
import com.framework.innolive.feature.live.components.PlatformDialog
import com.framework.innolive.feature.live.components.VerticalHeroButton
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import com.framework.innolive.feature.live.components.cappedDialogWidth
import com.framework.innolive.ui.text.asString
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
) {
    var openFaceManagement by remember { mutableStateOf(false) }
    var openPlatformDialog by remember { mutableStateOf(false) }
    var openYouTubeSettingsDialog by remember { mutableStateOf(false) }
    var openBroadcastActions by remember { mutableStateOf(false) }
    var pendingYouTubeSettingsDialog by remember { mutableStateOf(false) }
    var selectedPlatform by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val presentation = buildLiveScreenPresentation(
        connectionState = webRtcSession.connectionState,
        broadcastState = webRtcSession.broadcastState,
        selectedPlatform = selectedPlatform,
        broadcastStatus = webRtcSession.broadcastStatus,
        isBroadcastStatusDefault = webRtcSession.isBroadcastStatusDefault,
        isPreparingBroadcast = webRtcSession.isPreparingBroadcast,
    )
    val broadcastDurationText = rememberBroadcastDurationText(
        webRtcSession.broadcastStartedAtElapsedRealtimeMillis,
    )
    val broadcastDurationDescription = stringResource(
        R.string.content_description_broadcast_duration,
        broadcastDurationText,
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
        LiveMediaPermissionContent(
            permissionState = mediaPermissionState,
            onRequestPermissions = requestMissingMediaPermissions,
        ) {
            LiveVideoPanels(
                cameraLensFacing = props.cameraLensFacing,
                cameraResolution = props.cameraResolution,
                frameAnalyzer = webRtcSession.frameAnalyzer,
                lockedRotation = webRtcSession.lockedBroadcastRotation,
                remoteVideoTrack = webRtcSession.remoteVideoTrack,
                eglContext = webRtcSession.eglContext,
                isConnected = presentation.isConnected,
                modifier = Modifier.fillMaxSize(),
            )
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
                    contentDescription = stringResource(R.string.content_description_settings),
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
                    text = broadcastDurationText,
                    modifier = Modifier.semantics {
                        contentDescription = broadcastDurationDescription
                    },
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
                    contentDescription = stringResource(R.string.content_description_switch_camera),
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
                onBroadcastAction = {
                    when (presentation.broadcastAction) {
                        LiveBroadcastAction.SHOW_BROADCAST_ACTIONS -> openBroadcastActions = true
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
                            contentDescription = stringResource(R.string.face_management),
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
                    if (openBroadcastActions) {
                        BroadcastActionDialog(
                            presentation = presentation,
                            onDismiss = { openBroadcastActions = false },
                            onGoLive = {
                                openBroadcastActions = false
                                val activity = context as? Activity
                                val rotation = activity?.display?.rotation
                                if (activity != null && rotation != null) {
                                    val previousOrientation = activity.requestedOrientation
                                    val screenOrientation = screenOrientationFor(
                                        rotation,
                                        activity.resources.configuration.orientation,
                                    )
                                    if (!webRtcSession.goLive(rotation, screenOrientation) {
                                            activity.requestedOrientation = screenOrientation
                                        }) {
                                        activity.requestedOrientation = previousOrientation
                                    }
                                }
                            },
                            onCancelPreparation = {
                                openBroadcastActions = false
                                webRtcSession.stopBroadcast()
                            },
                            onPauseOrResume = {
                                openBroadcastActions = false
                                if (presentation.isBroadcastPaused) {
                                    webRtcSession.resumeBroadcast()
                                } else {
                                    webRtcSession.pauseBroadcast()
                                }
                            },
                            onStop = {
                                openBroadcastActions = false
                                webRtcSession.stopBroadcast()
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
            if (webRtcSession.connectionState == WebRtcConnectionState.FAILED ||
                webRtcSession.connectionState == WebRtcConnectionState.RECONNECTING) {
                webRtcSession.connectionStatus?.let { status ->
                    Text(
                        status.asString(),
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            webRtcSession.anonymizationChange.errorMessage?.let { error ->
                Text(
                    text = stringResource(R.string.anonymization_error_retry, error.asString()),
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            StableBroadcastFeedback {
                presentation.broadcastStatusText?.let { status ->
                    Text(
                        text = status.asString(),
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (presentation.isBroadcastStatusError) Color.Red else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberBroadcastDurationText(startedAtElapsedRealtimeMillis: Long?): String {
    var elapsedMillis by remember(startedAtElapsedRealtimeMillis) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtElapsedRealtimeMillis) {
        val startedAt = startedAtElapsedRealtimeMillis ?: run {
            elapsedMillis = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            delay(1_000L - elapsedMillis % 1_000L)
        }
    }
    return formatBroadcastDuration(elapsedMillis)
}

private fun mediaPermissionGuidance(state: MediaPermissionState): Int = when {
    !state.hasCameraPermission && !state.hasMicrophonePermission -> R.string.permission_media_required
    !state.hasCameraPermission -> R.string.permission_camera_required
    else -> R.string.permission_microphone_required
}

@Composable
internal fun StableBroadcastFeedback(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.heightIn(min = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
internal fun BroadcastActionControls(
    presentation: LiveScreenPresentation,
    onBroadcastAction: () -> Unit,
    leading: @Composable () -> Unit,
    centerOverlay: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit,
) {
    BalancedLiveControls(
        leading = leading,
        center = {
            Box(contentAlignment = Alignment.Center) {
                centerOverlay()
                VerticalHeroButton(
                    text = stringResource(presentation.broadcastButtonTextRes),
                    enabled = presentation.isBroadcastButtonEnabled,
                    onClick = onBroadcastAction,
                )
            }
        },
        trailing = trailing,
    )
}

@Composable
internal fun BroadcastActionDialog(
    presentation: LiveScreenPresentation,
    onDismiss: () -> Unit,
    onGoLive: () -> Unit,
    onCancelPreparation: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.cappedDialogWidth(400.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.broadcast_control_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (presentation.isBroadcastLive) {
                    Text(
                        text = stringResource(R.string.broadcast_pause_notice),
                        modifier = Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (presentation.isBroadcastPrepared) {
                    BroadcastDialogButton(stringResource(R.string.action_start_broadcast), onGoLive)
                    BroadcastDialogButton(
                        text = stringResource(R.string.action_cancel_preparation),
                        onClick = onCancelPreparation,
                        destructive = true,
                    )
                } else if (presentation.isBroadcastLive) {
                    BroadcastDialogButton(
                        text = stringResource(
                            if (presentation.isBroadcastPaused) R.string.action_resume_broadcast
                            else R.string.action_pause_broadcast,
                        ),
                        onClick = onPauseOrResume,
                    )
                    BroadcastDialogButton(
                        stringResource(R.string.action_stop_broadcast),
                        onStop,
                        destructive = true,
                    )
                }
                BroadcastDialogButton(stringResource(R.string.action_cancel), onDismiss)
            }
        }
    }
}

@Composable
internal fun LiveMediaPermissionContent(
    permissionState: MediaPermissionState,
    onRequestPermissions: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (permissionState.hasCameraPermission) preview()

        if (permissionState.missingPermissions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(mediaPermissionGuidance(permissionState)),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = Color.White,
                )
                Button(onClick = onRequestPermissions) {
                    Text(text = stringResource(R.string.action_allow_permissions))
                }
            }
        }
    }
}

@Composable
private fun BroadcastDialogButton(
    text: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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
        Box(
            modifier = Modifier.weight(5f),
            contentAlignment = Alignment.Center,
        ) {
            center()
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            trailing()
        }
    }
}
