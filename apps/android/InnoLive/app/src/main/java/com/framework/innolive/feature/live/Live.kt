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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.framework.innolive.feature.live.components.SessionUsageWarningBanner
import com.framework.innolive.feature.live.components.sessionNoticeMessageResource
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.feature.live.components.PlatformDialog
import com.framework.innolive.feature.live.components.ChzzkSettingsDialog
import com.framework.innolive.feature.live.components.ChzzkOAuthDialog
import com.framework.innolive.feature.live.components.VerticalHeroButton
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import com.framework.innolive.feature.live.components.cappedDialogWidth
import com.framework.innolive.feature.live.status.BroadcastLiveStatusPanel
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialAnchor
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialAnchors
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialCoordinator
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialDialog
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialHomeOverlay
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialHost
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialSnapshot
import com.framework.innolive.feature.live.tutorial.LocalBroadcastTutorialAnchors
import com.framework.innolive.feature.live.tutorial.broadcastTutorialAnchor
import com.framework.innolive.feature.live.tutorial.guideFor
import com.framework.innolive.ui.text.asString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
    tutorial: BroadcastTutorialCoordinator? = null,
) {
    val tutorialAnchors = remember { BroadcastTutorialAnchors() }
    CompositionLocalProvider(LocalBroadcastTutorialAnchors provides tutorialAnchors) {
        LiveScreenContent(props, webRtcSession, tutorial, tutorialAnchors)
    }
}

@Composable
private fun LiveScreenContent(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
    tutorial: BroadcastTutorialCoordinator?,
    tutorialAnchors: BroadcastTutorialAnchors,
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
    var chzzkAccountVerification by remember { mutableStateOf(ChzzkAccountVerification()) }
    var chzzkAccountRefreshKey by remember { mutableIntStateOf(0) }
    var chzzkBusy by remember { mutableStateOf(false) }
    var chzzkMessage by remember { mutableStateOf<UiText?>(null) }
    var openBroadcastActions by remember { mutableStateOf(false) }
    var pendingYouTubeSettingsDialog by remember { mutableStateOf(false) }
    var testUsageWarningOverride by remember { mutableStateOf(false) }
    var dismissedNoticeCodes by remember { mutableStateOf(emptySet<String>()) }
    val sessionSnapshot = webRtcSession.sessionSnapshot
    val sessionId = webRtcSession.sessionSnapshot?.sessionId
    val activeNoticeCode = if (testUsageWarningOverride) {
        "monthly_usage_80"
    } else {
        sessionSnapshot?.bannerNotices?.firstOrNull { notice ->
            notice.code !in dismissedNoticeCodes && sessionNoticeMessageResource(notice.code) != null
        }?.code
    }
    LaunchedEffect(sessionId) {
        dismissedNoticeCodes = emptySet()
        testUsageWarningOverride = false
    }
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
    fun finishChzzkAccountMutation(revision: Long) {
        if (chzzkAccountVerification.mutationRevision != revision) return
        chzzkAccountVerification = chzzkAccountVerification.finishMutation(revision)
        chzzkAccountRefreshKey++
    }
    LaunchedEffect(selectedPlatform) {
        if (selectedPlatform == "CHZZK") webRtcSession.selectProvider(BroadcastProvider.CHZZK)
        if (selectedPlatform == "YouTube") webRtcSession.selectProvider(BroadcastProvider.YOUTUBE)
        if (selectedPlatform != null) providerPreferences.edit().putString("selected", selectedPlatform).apply()
    }
    LaunchedEffect(openChzzkSettingsDialog, props.profileEmail, chzzkAccountRefreshKey) {
        val refreshing = chzzkAccountVerification.beginRefresh() ?: return@LaunchedEffect
        chzzkAccountVerification = refreshing
        val revision = refreshing.revision
        if (!openChzzkSettingsDialog) {
            chzzkBusy = false
            return@LaunchedEffect
        }
        if (openChzzkSettingsDialog && props.profileEmail.isBlank()) {
            chzzkMessage = UiText.Resource(R.string.chzzk_sign_in_required)
            chzzkBusy = false
        }
        if (openChzzkSettingsDialog && props.profileEmail.isNotBlank()) {
            chzzkBusy = true
            try {
                val account = checkNotNull(chzzkApi) { "서버 주소가 설정되지 않았습니다." }
                    .accounts(props.onRefreshAccessToken())
                    .firstOrNull { it.provider == "chzzk" }
                if (chzzkAccountVerification.revision == revision) {
                    chzzkAccountVerification = chzzkAccountVerification.confirm(revision, account)
                    chzzkMessage = null
                }
            } catch (_: Exception) {
                if (chzzkAccountVerification.revision == revision) {
                    chzzkMessage = UiText.Resource(R.string.chzzk_account_check_failed)
                }
            } finally {
                if (chzzkAccountVerification.revision == revision) chzzkBusy = false
            }
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
    val tutorialSnapshot = BroadcastTutorialSnapshot(
        openDialog = when {
            openPlatformDialog -> BroadcastTutorialDialog.PLATFORM
            openYouTubeSettingsDialog || openChzzkSettingsDialog -> BroadcastTutorialDialog.SETTINGS
            else -> BroadcastTutorialDialog.NONE
        },
        selectedAccountConnected = if (selectedPlatform == "CHZZK") {
            chzzkAccountVerification.canPrepare
        } else {
            props.hasYouTubeAccount && !props.isYouTubeReconnectRequired
        },
        broadcastState = webRtcSession.broadcastState,
        isPreparingBroadcast = webRtcSession.isPreparingBroadcast,
        hasStartedBroadcast = webRtcSession.broadcastStartedAtElapsedRealtimeMillis != null,
    )
    LaunchedEffect(tutorial, tutorialSnapshot) {
        tutorial?.update(tutorialSnapshot)
    }
    // 카메라·마이크 권한 안내가 떠 있는 동안에는 안내를 겹쳐 띄우지 않는다.
    val canShowTutorial = missingMediaPermissions.isEmpty()
    LaunchedEffect(tutorial, canShowTutorial) {
        if (tutorial == null || !canShowTutorial) return@LaunchedEffect
        // 카메라 미리보기가 먼저 보이도록 잠시 기다린 뒤 안내를 띄운다.
        delay(TUTORIAL_START_DELAY_MILLIS)
        tutorial.startIfNeeded()
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

        BroadcastTimeDisplay(
            uptime = broadcastDurationText,
            remaining = displayedRemainingTime(
                webRtcSession.broadcastStartedAtElapsedRealtimeMillis != null, sessionSnapshot, props.planUsage,
            ),
            isStale = webRtcSession.broadcastStartedAtElapsedRealtimeMillis != null &&
                sessionSnapshot?.isRemainingTimeStale == true,
            onOpenPlan = props.onOpenPlan,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp),
        )

        SessionUsageWarningBanner(
            noticeCode = activeNoticeCode,
            onDismissRequest = { code ->
                dismissedNoticeCodes = dismissedNoticeCodes + code
                if (testUsageWarningOverride && code == "monthly_usage_80") testUsageWarningOverride = false
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = 10.dp, top = 56.dp, end = 10.dp),
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
            if (BuildConfig.DEBUG) {
                TextButton(onClick = {
                    testUsageWarningOverride = !testUsageWarningOverride
                }) {
                    Text(
                        stringResource(
                            if (testUsageWarningOverride) R.string.session_usage_warning_test_hide
                            else R.string.session_usage_warning_test_show,
                        ),
                    )
                }
            }
            val statusTargets = sessionSnapshot?.visibleTargets.orEmpty()
            if (statusTargets.isNotEmpty()) {
                BroadcastLiveStatusPanel(
                    targets = statusTargets,
                    broadcastResolution = sessionSnapshot?.broadcastResolution?.takeIf { presentation.isConnected },
                    uplinkQuality = webRtcSession.uplinkQuality.takeIf { presentation.isConnected },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .broadcastTutorialAnchor(BroadcastTutorialAnchor.LIVE_STATUS),
                )
            }
            BroadcastActionControls(
                presentation = presentation,
                buttonModifier = Modifier.broadcastTutorialAnchor(BroadcastTutorialAnchor.PRIMARY_BUTTON),
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
                            guide = tutorial.guideFor(BroadcastTutorialHost.PLATFORM_DIALOG),
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
                            guide = tutorial.guideFor(BroadcastTutorialHost.SETTINGS_DIALOG),
                            onChangePlatform = {
                                openChzzkSettingsDialog = false
                                openPlatformDialog = true
                            },
                            accountLabel = when {
                                chzzkAccountVerification.mutationInProgress -> stringResource(R.string.chzzk_account_changing)
                                chzzkMessage != null -> chzzkMessage?.asString().orEmpty()
                                !chzzkAccountVerification.verified -> stringResource(R.string.chzzk_account_checking)
                                chzzkAccountVerification.account == null -> stringResource(R.string.chzzk_account_required)
                                chzzkAccountVerification.account?.reconnectRequired == true ->
                                    stringResource(R.string.chzzk_account_reconnect_required)
                                else -> stringResource(
                                    R.string.chzzk_account_connected,
                                    chzzkAccountVerification.account?.channelTitle.orEmpty(),
                                )
                            },
                            canPrepare = chzzkAccountVerification.canPrepare,
                            canConnect = props.profileEmail.isNotBlank(),
                            canDisconnect = chzzkAccountVerification.verified && chzzkAccountVerification.account != null,
                            isBusy = chzzkBusy || chzzkAccountVerification.mutationInProgress,
                            onChanged = { chzzkSettings = it },
                            onRefreshAccount = {
                                if (!chzzkAccountVerification.mutationInProgress) {
                                    chzzkAccountVerification = chzzkAccountVerification.invalidate()
                                    chzzkBusy = true
                                    chzzkAccountRefreshKey++
                                }
                            },
                            onConnect = connect@ {
                                if (chzzkAccountVerification.mutationInProgress) return@connect
                                val mutation = chzzkAccountVerification.beginMutation()
                                chzzkAccountVerification = mutation
                                chzzkBusy = true
                                chzzkMessage = null
                                scope.launch {
                                    var awaitingOAuth = false
                                    try {
                                        val state = newChzzkOAuthState()
                                        val config = checkNotNull(chzzkApi) { "서버 주소가 설정되지 않았습니다." }.config(state)
                                        if (chzzkAccountVerification.mutationRevision == mutation.revision) {
                                            chzzkOAuthState = state
                                            chzzkOAuthConfig = config
                                            awaitingOAuth = true
                                        }
                                    } catch (_: Exception) {
                                        if (chzzkAccountVerification.mutationRevision == mutation.revision) {
                                            chzzkMessage = UiText.Resource(R.string.chzzk_config_failed)
                                        }
                                    } finally {
                                        if (!awaitingOAuth) finishChzzkAccountMutation(mutation.revision)
                                    }
                                }
                            },
                            onDisconnect = disconnect@ {
                                if (chzzkAccountVerification.mutationInProgress) return@disconnect
                                val mutation = chzzkAccountVerification.beginMutation()
                                chzzkAccountVerification = mutation
                                chzzkBusy = true
                                chzzkMessage = null
                                scope.launch {
                                    try {
                                        checkNotNull(chzzkApi) { "서버 주소가 설정되지 않았습니다." }
                                            .disconnect(props.onRefreshAccessToken())
                                    } catch (_: Exception) {
                                        if (chzzkAccountVerification.mutationRevision == mutation.revision) {
                                            chzzkMessage = UiText.Resource(R.string.chzzk_disconnect_unconfirmed)
                                        }
                                    } finally {
                                        finishChzzkAccountMutation(mutation.revision)
                                    }
                                }
                            },
                            onSearch = { query -> checkNotNull(chzzkApi).categories(props.onRefreshAccessToken(), query) },
                            onPrepare = {
                                if (!chzzkAccountVerification.canPrepare || chzzkBusy) {
                                    chzzkMessage = UiText.Resource(R.string.chzzk_prepare_requires_account)
                                } else if (readMediaPermissionState(context).missingPermissions.isNotEmpty()) {
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
                    val currentMutationRevision = chzzkAccountVerification.mutationRevision
                    if (currentConfig != null && currentState != null && currentMutationRevision != null) {
                        ChzzkOAuthDialog(
                            config = currentConfig,
                            state = currentState,
                            onCode = { code ->
                                chzzkOAuthConfig = null
                                chzzkOAuthState = null
                                scope.launch {
                                    try {
                                        val api = checkNotNull(chzzkApi)
                                        api.connect(props.onRefreshAccessToken(), code, currentState)
                                    } catch (_: Exception) {
                                        if (chzzkAccountVerification.mutationRevision == currentMutationRevision) {
                                            chzzkMessage = UiText.Resource(R.string.chzzk_connect_unconfirmed)
                                        }
                                    } finally {
                                        finishChzzkAccountMutation(currentMutationRevision)
                                    }
                                }
                            },
                            onFailure = { reason ->
                                chzzkOAuthConfig = null
                                chzzkOAuthState = null
                                chzzkMessage = reason
                                finishChzzkAccountMutation(currentMutationRevision)
                            },
                            onDismiss = {
                                chzzkOAuthConfig = null
                                chzzkOAuthState = null
                                finishChzzkAccountMutation(currentMutationRevision)
                            },
                        )
                    }
                    if (openYouTubeSettingsDialog) {
                        YouTubeLiveSettingsDialog(
                            settings = props.broadcastSettings,
                            guide = tutorial.guideFor(BroadcastTutorialHost.SETTINGS_DIALOG),
                            onChangePlatform = {
                                openYouTubeSettingsDialog = false
                                openPlatformDialog = true
                            },
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

        if (tutorial != null) {
            BroadcastTutorialHomeOverlay(
                tutorial = tutorial,
                anchors = tutorialAnchors,
                isEnabled = canShowTutorial,
            )
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
                    ServerErrorAction.PLAN -> {
                        webRtcSession.dismissServerError()
                        props.onOpenPlan()
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

private const val TUTORIAL_START_DELAY_MILLIS = 600L

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
    buttonModifier: Modifier = Modifier,
    centerOverlay: @Composable () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        centerOverlay()
        VerticalHeroButton(
            text = stringResource(presentation.broadcastButtonTextRes),
            enabled = presentation.isBroadcastButtonEnabled,
            onClick = onBroadcastAction,
            modifier = buttonModifier,
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
