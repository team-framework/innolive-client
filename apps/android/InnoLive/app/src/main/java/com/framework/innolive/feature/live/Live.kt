package com.framework.innolive.feature.live

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
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
import com.framework.innolive.feature.face.LocalFaceManagementScreen
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.components.ServerErrorDialog
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.feature.live.components.PlatformDialog
import com.framework.innolive.feature.live.components.ChzzkSettingsDialog
import com.framework.innolive.feature.live.components.ChzzkOAuthDialog
import com.framework.innolive.feature.live.components.VerticalHeroButton
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import com.framework.innolive.feature.live.components.cappedDialogWidth
import com.framework.innolive.ui.text.asString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
) {
    var openVideoControls by remember { mutableStateOf(false) }
    var openFaceManagement by remember { mutableStateOf(false) }
    var openPlatformDialog by remember { mutableStateOf(false) }
    var openYouTubeSettingsDialog by remember { mutableStateOf(false) }
    var openChzzkSettingsDialog by remember { mutableStateOf(false) }
    var chzzkOAuthConfig by remember { mutableStateOf<ChzzkOAuthConfig?>(null) }
    var chzzkOAuthState by remember { mutableStateOf<String?>(null) }
    var chzzkSettings by rememberSaveable(stateSaver = ChzzkSettingsSaver) {
        mutableStateOf(ChzzkBroadcastSettings())
    }
    var chzzkAccount by remember { mutableStateOf<com.framework.innolive.feature.youtube.StreamingAccount?>(null) }
    var chzzkAccountVerified by remember { mutableStateOf(false) }
    var chzzkBusy by remember { mutableStateOf(false) }
    var chzzkMessage by remember { mutableStateOf<String?>(null) }
    var openBroadcastActions by remember { mutableStateOf(false) }
    var pendingYouTubeSettingsDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val providerPreferences = remember(context) {
        context.getSharedPreferences("innolive_broadcast_provider", android.content.Context.MODE_PRIVATE)
    }
    var selectedPlatform by rememberSaveable {
        mutableStateOf(providerPreferences.getString("selected", null))
    }
    val scope = rememberCoroutineScope()
    val chzzkApi = remember { runCatching { ChzzkApi(BuildConfig.INNOLIVE_SERVER_URL) }.getOrNull() }
    DisposableEffect(chzzkApi) { onDispose { chzzkApi?.close() } }
    LaunchedEffect(selectedPlatform) {
        if (selectedPlatform == "CHZZK") webRtcSession.selectProvider(BroadcastProvider.CHZZK)
        if (selectedPlatform == "YouTube") webRtcSession.selectProvider(BroadcastProvider.YOUTUBE)
        if (selectedPlatform != null) providerPreferences.edit().putString("selected", selectedPlatform).apply()
    }
    LaunchedEffect(openChzzkSettingsDialog, props.profileEmail) {
        chzzkAccountVerified = false
        chzzkAccount = null
        if (openChzzkSettingsDialog && props.profileEmail.isBlank()) {
            chzzkMessage = "먼저 로그인하세요."
        }
        if (openChzzkSettingsDialog && props.profileEmail.isNotBlank()) {
            chzzkBusy = true
            try {
                chzzkAccount = checkNotNull(chzzkApi) { "서버 주소가 설정되지 않았습니다." }
                    .accounts(props.onRefreshAccessToken())
                    .firstOrNull { it.provider == "chzzk" }
                chzzkAccountVerified = true
                chzzkMessage = null
            } catch (_: Exception) {
                chzzkMessage = "계정 상태를 확인하지 못했습니다. 로그인 후 다시 시도하세요."
            } finally { chzzkBusy = false }
        }
    }
    val idleFrameAnalyzer = remember { CameraFrameAnalyzer() }
    val frameAnalyzer = webRtcSession.frameAnalyzer ?: idleFrameAnalyzer
    val lookPreviews by frameAnalyzer.lookPreviews.collectAsState()
    DisposableEffect(idleFrameAnalyzer) {
        onDispose { idleFrameAnalyzer.close() }
    }
    DisposableEffect(frameAnalyzer, openVideoControls) {
        frameAnalyzer.setLookPreviewEnabled(openVideoControls)
        onDispose { frameAnalyzer.setLookPreviewEnabled(false) }
    }
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
    LaunchedEffect(webRtcSession.serverError) {
        if (webRtcSession.serverError?.action == ServerErrorAction.EDIT_SETTINGS) {
            openBroadcastActions = false
            openYouTubeSettingsDialog = true
        }
    }
    LaunchedEffect(webRtcSession) {
        webRtcSession.restoreAnonymizationSelection(context)
        webRtcSession.restoreAIProcessingSelection(context)
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
        if (webRtcSession.selectedOnDeviceProcessing) {
            LocalFaceManagementScreen(
                cameraLensFacing = props.cameraLensFacing,
                accountScope = props.onGetAccessToken()?.let { token ->
                    runCatching { sessionRecoveryScope(BuildConfig.INNOLIVE_SERVER_URL, token).storageKey }.getOrNull()
                },
                onBack = { openFaceManagement = false; webRtcSession.localFacesChanged() },
                onChanged = webRtcSession::localFacesChanged,
            )
        } else {
            FaceManagementScreen(
                cameraLensFacing = props.cameraLensFacing,
                onGetAccessToken = props.onGetAccessToken,
                onRefreshAccessToken = props.onRefreshAccessToken,
                onBack = { openFaceManagement = false },
                profileEmail = props.profileEmail,
            )
        }
        return
    }

    if (openVideoControls) {
        BroadcastVideoControls(
            settings = props.videoQualitySettings,
            captureState = props.videoQualityCaptureState,
            previews = lookPreviews?.previews,
            onSettingsChanged = props.onVideoQualitySettingsChanged,
            onDismiss = { openVideoControls = false },
            mirrorPreviews = props.cameraLensFacing.shouldMirrorPreview,
        )
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
                frameAnalyzer = frameAnalyzer,
                lockedRotation = webRtcSession.lockedBroadcastRotation,
                remoteVideoTrack = webRtcSession.remoteVideoTrack,
                localVideoTrack = webRtcSession.localVideoTrack,
                videoQualitySettings = props.videoQualitySettings,
                onVideoQualityCaptureStateChanged = props.onVideoQualityCaptureStateChanged,
                eglContext = webRtcSession.eglContext,
                isConnected = presentation.isConnected,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Text(
            text = broadcastDurationText,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .semantics { contentDescription = broadcastDurationDescription },
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )

        LiveSideControls(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            canOpenSettings = !presentation.isConnecting,
            canSwitchCamera = props.canSwitchCamera && !presentation.isConnecting,
            canManageFace = canManageFace,
            onOpenSettings = props.onOpenSettings,
            onOpenVideoControls = { openVideoControls = true },
            onSwitchCamera = props.onSwitchCamera,
            onOpenFaceManagement = {
                if (webRtcSession.connectionState != WebRtcConnectionState.CONNECTED) {
                    webRtcSession.close()
                }
                openFaceManagement = true
            },
            anonymizationState = anonymizationControlsState(
                webRtcSession.connectionState,
                webRtcSession.anonymizationState,
                webRtcSession.selectedAnonymizationEnabled,
                webRtcSession.isAnonymizationSelectionLoaded,
                webRtcSession.anonymizationChange,
            ),
            onAnonymizationSelect = { enabled -> webRtcSession.selectAnonymization(context, enabled) },
        )

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
                        LiveBroadcastAction.PREPARE_BROADCAST -> {
                            if (selectedPlatform == "CHZZK") openChzzkSettingsDialog = true
                            else openYouTubeSettingsDialog = true
                        }
                        LiveBroadcastAction.SELECT_PLATFORM -> openPlatformDialog = true
                    }
                },
                centerOverlay = {
                    if (openPlatformDialog) {
                        PlatformDialog(
                            onDismissRequest = {
                                openPlatformDialog = false
                            },
                            onYouTubeSelected = {
                                webRtcSession.selectProvider(BroadcastProvider.YOUTUBE)
                                selectedPlatform = "YouTube"
                                pendingYouTubeSettingsDialog = true
                                openPlatformDialog = false
                            },
                            onChzzkSelected = {
                                webRtcSession.selectProvider(BroadcastProvider.CHZZK)
                                selectedPlatform = "CHZZK"
                                openChzzkSettingsDialog = true
                                openPlatformDialog = false
                            },
                        )
                    }
                    if (openChzzkSettingsDialog) {
                        ChzzkSettingsDialog(
                            settings = chzzkSettings,
                            accountLabel = chzzkMessage ?: when {
                                !chzzkAccountVerified -> "계정 상태 확인 중"
                                chzzkAccount == null -> "치지직 계정을 연결하세요."
                                chzzkAccount?.reconnectRequired == true -> "치지직 계정을 다시 연결하세요."
                                else -> "연결됨: ${chzzkAccount?.channelTitle.orEmpty()}"
                            },
                            canPrepare = chzzkAccountVerified && chzzkAccount != null &&
                                chzzkAccount?.reconnectRequired == false,
                            canConnect = props.profileEmail.isNotBlank(),
                            canDisconnect = chzzkAccountVerified && chzzkAccount != null,
                            isBusy = chzzkBusy,
                            onChanged = { chzzkSettings = it },
                            onConnect = {
                                scope.launch {
                                    chzzkBusy = true
                                    try {
                                        val state = newChzzkOAuthState()
                                        val config = checkNotNull(chzzkApi) { "서버 주소가 설정되지 않았습니다." }.config(state)
                                        chzzkOAuthState = state
                                        chzzkOAuthConfig = config
                                        chzzkMessage = null
                                    } catch (_: Exception) {
                                        chzzkMessage = "치지직 연동 설정을 받지 못했습니다. 서버 설정을 확인하세요."
                                    } finally { chzzkBusy = false }
                                }
                            },
                            onDisconnect = {
                                scope.launch {
                                    chzzkBusy = true
                                    try {
                                        checkNotNull(chzzkApi) { "서버 주소가 설정되지 않았습니다." }
                                            .disconnect(props.onRefreshAccessToken())
                                        chzzkAccount = null
                                        chzzkAccountVerified = true
                                        chzzkMessage = null
                                    } catch (_: Exception) {
                                        chzzkMessage = "연결을 해제하지 못했습니다. 다시 시도하세요."
                                    } finally { chzzkBusy = false }
                                }
                            },
                            onSearch = { query -> checkNotNull(chzzkApi).categories(props.onRefreshAccessToken(), query) },
                            onPrepare = {
                                if (readMediaPermissionState(context).missingPermissions.isNotEmpty()) {
                                    mediaPermissions.refresh()
                                    mediaPermissionLauncher.launch(readMediaPermissionState(context).missingPermissions.toTypedArray())
                                } else if (webRtcSession.prepareChzzkBroadcast(context, chzzkSettings,
                                        props.onRefreshAccessToken)) {
                                    openChzzkSettingsDialog = false
                                }
                            },
                            onDismiss = { openChzzkSettingsDialog = false },
                        )
                    }
                    val currentConfig = chzzkOAuthConfig
                    val currentState = chzzkOAuthState
                    if (currentConfig != null && currentState != null) {
                        ChzzkOAuthDialog(
                            config = currentConfig,
                            state = currentState,
                            onCode = { code ->
                                chzzkOAuthConfig = null
                                chzzkOAuthState = null
                                scope.launch {
                                    chzzkBusy = true
                                    try {
                                        val api = checkNotNull(chzzkApi)
                                        api.connect(props.onRefreshAccessToken(), code, currentState)
                                        chzzkAccount = api.accounts(props.onRefreshAccessToken())
                                            .firstOrNull { it.provider == "chzzk" }
                                        chzzkAccountVerified = true
                                        chzzkMessage = if (chzzkAccount == null) "연결 상태를 다시 확인하세요." else null
                                    } catch (_: Exception) {
                                        chzzkMessage = "치지직 연결에 실패했습니다. 다시 연결하세요."
                                    } finally { chzzkBusy = false }
                                }
                            },
                            onFailure = { reason ->
                                chzzkOAuthConfig = null
                                chzzkOAuthState = null
                                chzzkMessage = reason
                            },
                            onDismiss = { chzzkOAuthConfig = null; chzzkOAuthState = null },
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
                            serverError = webRtcSession.serverError?.takeIf {
                                it.action == ServerErrorAction.EDIT_SETTINGS
                            },
                            onSettingsChanged = {
                                webRtcSession.dismissServerError()
                                props.onBroadcastSettingsChanged(it)
                            },
                            onConnectYouTube = props.onConnectYouTube,
                            onDismissRequest = {
                                openYouTubeSettingsDialog = false
                                webRtcSession.dismissServerError()
                            },
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
    webRtcSession.serverError?.takeIf { it.action != ServerErrorAction.EDIT_SETTINGS }?.let { guidance ->
        ServerErrorDialog(
            guidance = guidance,
            onDismiss = webRtcSession::dismissServerError,
            onAction = {
                when (guidance.action) {
                    ServerErrorAction.CONFIRM_CONCURRENT -> webRtcSession.confirmConcurrentBroadcast()
                    ServerErrorAction.RETRY -> webRtcSession.retryBroadcast()
                    ServerErrorAction.CONNECT, ServerErrorAction.RECONNECT -> {
                        webRtcSession.dismissServerError()
                        openYouTubeSettingsDialog = true
                        props.onConnectYouTube()
                    }
                    ServerErrorAction.LOGIN -> {
                        webRtcSession.dismissServerError()
                        props.onAuthenticationExpired()
                    }
                    else -> webRtcSession.dismissServerError()
                }
            },
        )
    }
}

@Composable
internal fun LiveSideControls(
    modifier: Modifier = Modifier,
    canOpenSettings: Boolean,
    canSwitchCamera: Boolean,
    canManageFace: Boolean,
    onOpenSettings: () -> Unit,
    onOpenVideoControls: () -> Unit,
    onSwitchCamera: () -> Unit,
    onOpenFaceManagement: () -> Unit,
    anonymizationState: AnonymizationControlsState,
    onAnonymizationSelect: (Boolean) -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onOpenSettings, enabled = canOpenSettings, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.settings),
                contentDescription = stringResource(R.string.content_description_settings),
                modifier = Modifier.size(28.dp),
                tint = Color.White,
            )
        }
        IconButton(onClick = onOpenVideoControls, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.video_controls_title),
                tint = Color.White,
            )
        }
        IconButton(onClick = onSwitchCamera, enabled = canSwitchCamera, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.change_camera),
                contentDescription = stringResource(R.string.content_description_switch_camera),
                modifier = Modifier.size(28.dp),
                tint = Color.White,
            )
        }
        IconButton(onClick = onOpenFaceManagement, enabled = canManageFace, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.Face,
                contentDescription = stringResource(R.string.face_management),
                modifier = Modifier.size(32.dp),
                tint = Color.White,
            )
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            AnonymizationControls(
                state = anonymizationState,
                onSelect = onAnonymizationSelect,
            )
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
    centerOverlay: @Composable () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        centerOverlay()
        VerticalHeroButton(
            text = stringResource(presentation.broadcastButtonTextRes),
            enabled = presentation.isBroadcastButtonEnabled,
            onClick = onBroadcastAction,
        )
    }
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
                    BroadcastDialogButton(
                        stringResource(R.string.action_start_broadcast),
                        onGoLive,
                        enabled = presentation.canStartOrResumeBroadcast,
                    )
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
                        enabled = !presentation.isBroadcastPaused || presentation.canStartOrResumeBroadcast,
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
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
