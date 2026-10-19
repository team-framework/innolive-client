package com.framework.innolive.feature.live

import com.framework.innolive.ui.theme.ModalSurface
import com.framework.innolive.ui.theme.InnoLiveDialog
import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framework.innolive.R
import com.framework.innolive.feature.face.FaceManagementScreen
import com.framework.innolive.feature.face.FaceSelectionLayer
import com.framework.innolive.feature.face.GuestFaceName
import com.framework.innolive.feature.face.GuestFaceRegistrar
import com.framework.innolive.feature.face.GuestFaceTarget
import com.framework.innolive.feature.face.GuestRegistrationToast
import com.framework.innolive.feature.face.LocalFaceManagementScreen
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.components.BroadcastModeDialog
import com.framework.innolive.feature.live.components.BroadcastSettingsMenu
import com.framework.innolive.feature.live.components.BroadcastSettingsMenuItem
import com.framework.innolive.feature.live.components.ChzzkSettingsDialog
import com.framework.innolive.feature.live.components.ServerErrorDialog
import com.framework.innolive.feature.live.components.logoRes
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import com.framework.innolive.feature.live.consent.BroadcastConsentDialog
import com.framework.innolive.feature.live.consent.BroadcastConsentStep
import com.framework.innolive.feature.live.consent.BroadcastConsentStore
import com.framework.innolive.feature.live.consent.nextBroadcastConsentStep
import com.framework.innolive.feature.live.components.validateYouTubeLiveSettings
import com.framework.innolive.feature.live.direct.broadcast.DirectProviderOrder
import com.framework.innolive.feature.live.direct.broadcast.labelRes
import com.framework.innolive.feature.live.direct.broadcast.title
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.DirectPublishFeature
import com.framework.innolive.feature.live.direct.lifecycle.rememberBroadcastNotificationPermission
import com.framework.innolive.feature.live.direct.lifecycle.shouldShowNotificationsHiddenNotice
import com.framework.innolive.feature.live.components.SessionUsageWarningBanner
import com.framework.innolive.feature.live.components.sessionNoticeMessageResource
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.feature.live.components.ChzzkOAuthDialog
import com.framework.innolive.feature.live.components.VerticalHeroButton
import com.framework.innolive.feature.live.components.cappedDialogWidth
import com.framework.innolive.feature.live.direct.broadcast.directBroadcastModeState
import com.framework.innolive.feature.live.direct.broadcast.directPlanRows
import com.framework.innolive.feature.live.direct.broadcast.directStatusWarnings
import com.framework.innolive.feature.live.direct.broadcast.directTargetRows
import com.framework.innolive.feature.live.direct.broadcast.isDirectBroadcastModeLocked
import com.framework.innolive.feature.live.direct.broadcast.reconcileDirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.withSelectedPlatform
import com.framework.innolive.feature.live.status.BroadcastLiveStatusChip
import com.framework.innolive.feature.live.status.BroadcastLiveStatusWarnings
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialAnchor
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialAnchors
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialCoordinator
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialDialog
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialHomeOverlay
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialHost
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialSnapshot
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialStep
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
    /** 없으면 카메라·기본 화면만 넘깁니다. */
    settings: LiveSettingsPage? = null,
) {
    val tutorialAnchors = remember { BroadcastTutorialAnchors() }
    // 얼굴 관리 화면을 열었다 닫아도 보던 페이지로 돌아오도록 화면 내용 밖에서 기억합니다.
    val pagerState = rememberPagerState(
        initialPage = if (settings?.isOpen == true) LiveStudioPage.SETTINGS.ordinal else LiveStudioPage.HOME.ordinal,
    ) { if (settings == null) LiveStudioPage.SETTINGS.ordinal else LiveStudioPage.entries.size }
    val pipState = rememberLivePreviewPipState()
    CompositionLocalProvider(LocalBroadcastTutorialAnchors provides tutorialAnchors) {
        LiveScreenContent(props, webRtcSession, tutorial, tutorialAnchors, pagerState, settings, pipState)
    }
}

@Composable
private fun LiveScreenContent(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
    tutorial: BroadcastTutorialCoordinator?,
    tutorialAnchors: BroadcastTutorialAnchors,
    pagerState: PagerState,
    settings: LiveSettingsPage?,
    pipState: LivePreviewPipState,
) {
    var openVideoControls by remember { mutableStateOf(false) }
    var openFaceManagement by remember { mutableStateOf(false) }
    // 방송 카메라가 켜진 뒤 연 얼굴 관리는 방송을 끊지 않고 그 위에 띄웁니다.
    var faceManagementUsesBroadcastCamera by remember { mutableStateOf(false) }
    // 홈 화면 원탭 얼굴 등록입니다. 선택 중에는 원본 카메라를 메인에 두고 송출 화면을 작은 창으로 보냅니다.
    var isSelectingFace by remember { mutableStateOf(false) }
    // 마지막으로 열린 카메라 방향입니다. 고른 방향과 다르면 카메라를 바꾸는 중입니다.
    var openedCameraLensFacing by remember { mutableStateOf<CameraLensFacing?>(null) }
    // 방송 설정 창 하나에서 화질·플랫폼·플랫폼별 설정을 고릅니다. 탭은 열 때 보여 줄 플랫폼입니다.
    var openSettingsDialog by remember { mutableStateOf(false) }
    var openYouTubeEditor by remember { mutableStateOf(false) }
    var openChzzkEditor by remember { mutableStateOf(false) }
    var openModeEditor by remember { mutableStateOf(false) }
    /** 방송 설정 메뉴를 열고, [editor]가 있으면 그 플랫폼 설정 창을 위에 바로 엽니다. */
    fun openBroadcastSettings(editor: BroadcastProvider? = null) {
        openSettingsDialog = true
        when (editor) {
            BroadcastProvider.YOUTUBE -> openYouTubeEditor = true
            BroadcastProvider.CHZZK -> openChzzkEditor = true
            null -> Unit
        }
    }
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
    // 방송 준비 중 표시할 동의·안내입니다. 수락하면 다음 단계로 이어서 진행합니다.
    var pendingConsentStep by remember { mutableStateOf<BroadcastConsentStep?>(null) }
    var testUsageWarningOverride by remember { mutableStateOf(false) }
    // 사용자가 방송 방식을 직접 고르기 전에는 요금제가 허용하는 최고 화질을 따릅니다.
    var directModeUserSelected by rememberSaveable { mutableStateOf(false) }
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
        context.getSharedPreferences(BROADCAST_PROVIDER_PREFERENCES, android.content.Context.MODE_PRIVATE)
    }
    var selectedPlatform by rememberSaveable {
        mutableStateOf(providerPreferences.getString("selected", null))
    }
    val broadcastConsentStore = remember(context) { BroadcastConsentStore(context) }
    val scope = rememberCoroutineScope()
    // 탭한 게스트는 선택이 끝난 뒤에도 백그라운드에서 등록을 이어 가므로 화면 수준에서 보관합니다.
    val guestRegistrar = remember(context) { GuestFaceRegistrar(context, scope) }
    DisposableEffect(guestRegistrar) { onDispose { guestRegistrar.close() } }
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
        if (selectedPlatform == "SIMULCAST") webRtcSession.selectProviders(SimulcastProviders)
        if (selectedPlatform != null) providerPreferences.edit().putString("selected", selectedPlatform).apply()
    }
    // 방송 설정 창은 치지직 계정 연결 여부로 선택지를 막으므로 열려 있는 동안 계정을 확인합니다.
    val needsChzzkAccount = openSettingsDialog || openChzzkEditor || openModeEditor
    LaunchedEffect(needsChzzkAccount, props.profileEmail, chzzkAccountRefreshKey) {
        val refreshing = chzzkAccountVerification.beginRefresh() ?: return@LaunchedEffect
        chzzkAccountVerification = refreshing
        val revision = refreshing.revision
        if (!needsChzzkAccount) {
            chzzkBusy = false
            return@LaunchedEffect
        }
        if (props.profileEmail.isBlank()) {
            chzzkMessage = UiText.Resource(R.string.chzzk_sign_in_required)
            chzzkBusy = false
        }
        if (props.profileEmail.isNotBlank()) {
            chzzkBusy = true
            try {
                val account = checkNotNull(chzzkApi) { "서버 주소가 설정되지 않았습니다." }
                    .accounts(props.onRefreshAccessToken())
                    .firstOrNull { it.provider == "chzzk" }
                if (chzzkAccountVerification.revision == revision) {
                    chzzkAccountVerification = chzzkAccountVerification.confirm(revision, account)
                    chzzkMessage = null
                    // 서버에서 연결이 없음을 확인하면 다음 연결 후 방송 준비에서 전송 안내를 다시 받습니다.
                    if (account == null) {
                        runCatching { broadcastConsentStore.clear(BroadcastConsentStep.CHZZK_TRANSMISSION) }
                    }
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
    val isDirectModeLocked = isDirectBroadcastModeLocked(
        webRtcSession.broadcastState, webRtcSession.isPreparingBroadcast,
    )
    LaunchedEffect(isDirectModeLocked, props.planUsage, directModeUserSelected) {
        if (isDirectModeLocked) return@LaunchedEffect
        val reconciled = reconcileDirectBroadcastPlan(
            webRtcSession.directBroadcastPlan, props.planUsage, directModeUserSelected,
        )
        if (reconciled != webRtcSession.directBroadcastPlan) webRtcSession.selectDirectBroadcastPlan(reconciled)
    }
    // 저장된 플랫폼 선택을 두 처리 방식이 함께 쓰는 방송 구성에 맞춥니다.
    LaunchedEffect(isDirectModeLocked, selectedPlatform) {
        if (isDirectModeLocked) return@LaunchedEffect
        val plan = webRtcSession.directBroadcastPlan
        val synced = when (selectedPlatform) {
            "YouTube" -> plan.withSelectedPlatform(BroadcastProvider.YOUTUBE)
            "CHZZK" -> plan.withSelectedPlatform(BroadcastProvider.CHZZK)
            "SIMULCAST" -> plan.copy(providers = SimulcastProviders)
            else -> return@LaunchedEffect
        }
        if (synced != webRtcSession.directBroadcastPlan) webRtcSession.selectDirectBroadcastPlan(synced)
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
    val broadcastNotificationPermission = rememberBroadcastNotificationPermission(context)
    // 직접 송출은 백그라운드 유지 알림을 띄우므로 준비 직후 알림 권한을 확인합니다. 거부해도 방송은 진행합니다.
    val requestDirectBroadcastNotifications = {
        if (webRtcSession.selectedOnDeviceProcessing && DirectPublishFeature.ENABLED) {
            broadcastNotificationPermission.ensure()
        }
    }
    LaunchedEffect(webRtcSession.serverError) {
        if (webRtcSession.serverError?.action == ServerErrorAction.EDIT_SETTINGS) {
            openBroadcastActions = false
            openBroadcastSettings()
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
        openDialog = if (openSettingsDialog || openYouTubeEditor || openChzzkEditor || openModeEditor) {
            BroadcastTutorialDialog.SETTINGS
        } else BroadcastTutorialDialog.NONE,
        selectedAccountConnected = when (selectedPlatform) {
            "CHZZK" -> chzzkAccountVerification.canPrepare
            "SIMULCAST" -> chzzkAccountVerification.canPrepare &&
                props.hasYouTubeAccount && !props.isYouTubeReconnectRequired
            else -> props.hasYouTubeAccount && !props.isYouTubeReconnectRequired
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

    if (openFaceManagement && !faceManagementUsesBroadcastCamera) {
        LiveFaceManagement(
            props = props,
            webRtcSession = webRtcSession,
            broadcastCamera = null,
            onClose = { openFaceManagement = false },
        )
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

    val canManageFaceWithBroadcastCamera =
        webRtcSession.frameAnalyzer != null &&
            webRtcSession.connectionState == WebRtcConnectionState.CONNECTED &&
            !webRtcSession.isPreparingBroadcast &&
            webRtcSession.broadcastState in FaceManagementBroadcastStates
    val canManageFace = canManageFaceWithBroadcastCamera ||
        webRtcSession.connectionState in setOf(
            WebRtcConnectionState.IDLE,
            WebRtcConnectionState.FAILED,
            WebRtcConnectionState.CONNECTED,
        ) && !webRtcSession.isPreparingBroadcast &&
            webRtcSession.broadcastState in setOf(BroadcastState.IDLE, BroadcastState.FAILED)
    val isSwitchingCamera = openedCameraLensFacing.let { it != null && it != props.cameraLensFacing }
    // 송출 카메라 프레임이 계속 들어오는 동안만 얼굴을 고릅니다. 연결·준비 중에는 카메라가 바뀔 수 있어 선택을 끝냅니다.
    // iOS처럼 카메라를 바꾸는 동안에는 선택을 시작하지 않습니다.
    val canSelectFace = mediaPermissionState.hasCameraPermission &&
        !isSwitchingCamera &&
        !presentation.isConnecting &&
        !webRtcSession.isPreparingBroadcast &&
        webRtcSession.broadcastState in FaceSelectionBroadcastStates
    LaunchedEffect(canSelectFace) {
        if (!canSelectFace) isSelectingFace = false
    }
    /** 방송에 고른 처리 방식과 같은 곳에 게스트를 등록합니다. */
    fun guestFaceTarget(): GuestFaceTarget = if (webRtcSession.selectedOnDeviceProcessing) {
        GuestFaceTarget.OnDevice(
            accountScope = props.onGetAccessToken()?.let { token ->
                runCatching { sessionRecoveryScope(BuildConfig.INNOLIVE_SERVER_URL, token).storageKey }.getOrNull()
            },
            onChanged = webRtcSession::localFacesChanged,
        )
    } else {
        GuestFaceTarget.Server(
            accountEmail = props.profileEmail,
            getAccessToken = props.onGetAccessToken,
            refreshAccessToken = props.onRefreshAccessToken,
        )
    }

    val anonymizationState = anonymizationControlsState(
        webRtcSession.connectionState,
        webRtcSession.anonymizationState,
        webRtcSession.selectedAnonymizationEnabled,
        webRtcSession.isAnonymizationSelectionLoaded,
        webRtcSession.anonymizationChange,
    )
    val openFaceManagementScreen = {
        isSelectingFace = false
        faceManagementUsesBroadcastCamera = canManageFaceWithBroadcastCamera
        if (
            !canManageFaceWithBroadcastCamera &&
            webRtcSession.connectionState != WebRtcConnectionState.CONNECTED
        ) {
            webRtcSession.close()
        }
        openFaceManagement = true
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 방송 상태는 상단의 칩 하나로 보여 줍니다. 방송 전에는 고른 구성을, 방송 중에는 플랫폼별 송출 상태를 보여 줍니다.
    val directUiState = webRtcSession.directBroadcastUiState
    val statusTargets = directTargetRows(sessionSnapshot?.visibleTargets.orEmpty(), directUiState)
    val nowElapsedMillis = rememberElapsedRealtime(ticking = directUiState.preparedDeadlineElapsedMillis != null)
    val directWarnings = directStatusWarnings(directUiState, nowElapsedMillis)
    val statusPlan = webRtcSession.directBroadcastPlan
    val showPlan = !isDirectModeLocked && statusTargets.isEmpty()
    val statusRows = if (showPlan) directPlanRows(statusPlan) else statusTargets
    val statusResolution = if (showPlan) statusPlan.resolution.wireValue
        else sessionSnapshot?.broadcastResolution?.takeIf { presentation.isConnected }
    val statusUplink = webRtcSession.uplinkQuality.takeIf { presentation.isConnected && !showPlan }
    val statusChip: @Composable (Modifier) -> Unit = { chipModifier ->
        if (statusRows.isNotEmpty()) {
            BroadcastLiveStatusChip(
                targets = statusRows,
                broadcastResolution = statusResolution,
                uplinkQuality = statusUplink,
                modifier = chipModifier.broadcastTutorialAnchor(BroadcastTutorialAnchor.LIVE_STATUS),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.Black),
    ) {
        LiveStudioPager(
            pagerState = pagerState,
            settings = settings,
            // 연결 중에는 설정 버튼처럼 설정 페이지로 넘기는 것도 막습니다.
            canSwipe = !presentation.isConnecting,
            // 얼굴을 고르는 동안에는 기본 화면에 머물고, 빈 곳을 누르면 아래 원본 영상의 얼굴 선택으로 전달합니다.
            pinnedToHome = tutorial?.stage != null || isSelectingFace,
            previewInteractive = isSelectingFace,
            pipState = pipState,
            preview = {
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
                        onDeviceProcessing = webRtcSession.selectedOnDeviceProcessing,
                        isDirectPublish = webRtcSession.selectedOnDeviceProcessing && DirectPublishFeature.ENABLED,
                        modifier = Modifier.fillMaxSize(),
                        pipState = pipState,
                        originalIsMain = isSelectingFace,
                        onCameraOpened = { openedCameraLensFacing = it },
                        mainOverlay = if (isSelectingFace) {
                            {
                                // iOS처럼 원본 위에서 얼굴을 고르면 백그라운드로 등록하고 바로 송출 화면으로 돌아갑니다.
                                FaceSelectionLayer(
                                    analyzer = frameAnalyzer,
                                    mirrored = props.cameraLensFacing.shouldMirrorPreview,
                                    onSelect = { frame, faceIndex ->
                                        guestRegistrar.register(
                                            frame, faceIndex, GuestFaceName.make(context), guestFaceTarget(),
                                        )
                                        isSelectingFace = false
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            },
            // 설정은 왼쪽으로 넘겨 열고, 상단에는 방송 시간을 둡니다. 가로 화면은 높이가 낮아 그 옆에 방송 상태를 붙입니다.
            topBar = {
                val isRemainingTimeStale = webRtcSession.broadcastStartedAtElapsedRealtimeMillis != null &&
                    sessionSnapshot?.isRemainingTimeStale == true
                val remaining = rememberRemainingCountdown(
                    remaining = displayedRemainingTime(
                        webRtcSession.broadcastStartedAtElapsedRealtimeMillis != null, sessionSnapshot, props.planUsage,
                    ),
                    // 송출 중인 세션 값만 줄인다. 요금제 미리보기나 일시 중지·갱신 지연 중에는 그대로 둔다.
                    counting = webRtcSession.broadcastState == BroadcastState.LIVE && !isRemainingTimeStale &&
                        sessionSnapshot?.remainingTime is BroadcastRemainingTime.Seconds,
                )
                if (landscape) {
                    Row(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BroadcastTimeDisplay(
                            uptime = broadcastDurationText,
                            remaining = remaining,
                            isStale = isRemainingTimeStale,
                            onOpenPlan = props.onOpenPlan,
                            compact = true,
                        )
                        statusChip(Modifier)
                    }
                } else {
                    BroadcastTimeDisplay(
                        uptime = broadcastDurationText,
                        remaining = remaining,
                        isStale = isRemainingTimeStale,
                        onOpenPlan = props.onOpenPlan,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp),
                    )
                }
            },
            // 카메라 화면은 조작 요소 없이 미리보기만 보여 줍니다.
            cameraPage = {},
            homePage = {
                val density = LocalDensity.current
                var pageSize by remember { mutableStateOf(IntSize.Zero) }
                var noticeHeightPx by remember { mutableIntStateOf(0) }
                var chipHeightPx by remember { mutableIntStateOf(0) }
                var toolsHeightPx by remember { mutableIntStateOf(0) }
                Box(Modifier.matchParentSize().onSizeChanged { pageSize = it })
                // 사용량 안내와 방송 주의 안내는 상단 바 바로 아래 가운데에 모읍니다.
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = LiveStudioTopInset).fillMaxWidth()
                        .onSizeChanged { noticeHeightPx = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SessionUsageWarningBanner(
                        noticeCode = activeNoticeCode,
                        onDismissRequest = { code ->
                            dismissedNoticeCodes = dismissedNoticeCodes + code
                            if (testUsageWarningOverride && code == "monthly_usage_80") testUsageWarningOverride = false
                        },
                        modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 10.dp),
                    )
                    BroadcastLiveStatusWarnings(
                        warnings = directWarnings,
                        uplinkQuality = statusUplink,
                        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp),
                    )
                }
                val noticeBottomPx = with(density) { LiveStudioTopInset.toPx() } + noticeHeightPx
                val rowTopPx = noticeBottomPx + with(density) { 8.dp.toPx() }
                // 세로 화면은 상태 칩을 왼쪽 위에, 카메라 도구를 오른쪽 위에 같은 높이로 둡니다.
                if (!landscape) {
                    Box(
                        Modifier.align(Alignment.TopStart)
                            .offset { IntOffset(0, rowTopPx.roundToInt()) }
                            .onSizeChanged { chipHeightPx = it.height }
                            .padding(start = 16.dp, end = 120.dp),
                    ) { statusChip(Modifier) }
                }
                // 작은 창은 안내와 상태 칩 아래에만 놓아 서로 가리지 않게 합니다.
                SideEffect {
                    pipState.topReservePx = if (!landscape && chipHeightPx > 0) rowTopPx + chipHeightPx else noticeBottomPx
                }
                if (!isSelectingFace) {
                    GuestRegistrationToast(
                        registrar = guestRegistrar,
                        onReselect = { if (canSelectFace) isSelectingFace = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset { IntOffset(0, rowTopPx.roundToInt()) }
                            .padding(horizontal = 24.dp),
                    )
                }
                if (landscape) {
                    // 가로 화면은 왼쪽 띠에 카메라 도구를 세로로 두어 왼손 엄지가 닿게 하고, 영상은 가리지 않습니다.
                    LiveCameraTools(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .onSizeChanged { pipState.leftControlsWidthPx = it.width.toFloat() }
                            .padding(start = 12.dp, end = 4.dp, top = LiveStudioTopInset, bottom = 12.dp),
                        canManageFace = canManageFace,
                        onOpenVideoControls = { openVideoControls = true },
                        onOpenFaceManagement = openFaceManagementScreen,
                        canSelectFace = canSelectFace,
                        isSelectingFace = isSelectingFace,
                        onToggleFaceSelection = { isSelectingFace = !isSelectingFace },
                    )
                } else {
                    SideEffect { pipState.leftControlsWidthPx = 0f }
                    // 오른쪽에 붙은 작은 창과 겹치면 창 아래나 왼쪽으로 비키고, 같은 스프링으로 따라 움직입니다.
                    val toolsTarget = with(density) {
                        cameraToolsPlacement(
                            naturalTop = rowTopPx,
                            toolsHeight = toolsHeightPx.toFloat(),
                            toolsRight = pageSize.width - 16.dp.toPx(),
                            maxTop = pageSize.height - pipState.bottomReservePx(this) - 12.dp.toPx() - toolsHeightPx,
                            pipBounds = pipState.restingBounds.takeIf { pipState.isVisible && pipState.alignEnd },
                            gap = 12.dp.toPx(),
                        )
                    }
                    val toolsSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)
                    val toolsTop by animateFloatAsState(toolsTarget.top, toolsSpring, label = "cameraToolsTop")
                    val toolsShiftLeft by animateFloatAsState(toolsTarget.shiftLeft, toolsSpring, label = "cameraToolsShift")
                    LiveCameraTools(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(-toolsShiftLeft.roundToInt(), toolsTop.roundToInt()) }
                            .padding(end = 12.dp)
                            .onSizeChanged { toolsHeightPx = it.height },
                        canManageFace = canManageFace,
                        onOpenVideoControls = { openVideoControls = true },
                        onOpenFaceManagement = openFaceManagementScreen,
                        canSelectFace = canSelectFace,
                        isSelectingFace = isSelectingFace,
                        onToggleFaceSelection = { isSelectingFace = !isSelectingFace },
                    )
                }

                val switchCameraButton: @Composable () -> Unit = {
                    IconButton(
                        onClick = props.onSwitchCamera,
                        // 선택 중 카메라가 바뀌면 고르던 얼굴 좌표와 좌우 반전이 맞지 않으므로 전환을 막습니다.
                        enabled = props.canSwitchCamera && !presentation.isConnecting && !isSelectingFace,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.change_camera),
                            contentDescription = stringResource(R.string.content_description_switch_camera),
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                }
                val anonymizationButton: @Composable () -> Unit = {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        AnonymizationControls(
                            state = anonymizationState,
                            onSelect = { enabled -> webRtcSession.selectAnonymization(context, enabled) },
                        )
                    }
                }
                val broadcastControls: @Composable (Modifier) -> Unit = { controlsModifier ->
                    BroadcastActionControls(
                        modifier = controlsModifier,
                        vertical = landscape,
                        presentation = presentation,
                        buttonModifier = Modifier.broadcastTutorialAnchor(BroadcastTutorialAnchor.PRIMARY_BUTTON),
                        onBroadcastAction = {
                            when (presentation.broadcastAction) {
                                LiveBroadcastAction.SHOW_BROADCAST_ACTIONS -> openBroadcastActions = true
                                LiveBroadcastAction.PREPARE_BROADCAST,
                                LiveBroadcastAction.SELECT_PLATFORM -> openBroadcastSettings()
                            }
                        },
                        centerOverlay = {
                            val providers = webRtcSession.directBroadcastPlan.providers
                            val youtubeReady = props.hasYouTubeAccount && !props.isYouTubeReconnectRequired &&
                                !props.isYouTubeAccountActionInProgress
                            val chzzkReady = chzzkAccountVerification.canPrepare && !chzzkBusy
                            val broadcastPlanState = directBroadcastModeState(
                                plan = webRtcSession.directBroadcastPlan,
                                planUsage = props.planUsage,
                                accountConnected = { provider ->
                                    when (provider) {
                                        BroadcastProvider.YOUTUBE -> props.hasYouTubeAccount && !props.isYouTubeReconnectRequired
                                        BroadcastProvider.CHZZK -> chzzkAccountVerification.canPrepare
                                    }
                                },
                                locked = isDirectModeLocked,
                            )
                            val settingsGuide = tutorial.guideFor(BroadcastTutorialHost.SETTINGS_DIALOG)
                            val editorOpen = openYouTubeEditor || openChzzkEditor || openModeEditor
                            // 편집 창이 메뉴를 가리므로 계정 연결 안내는 실제 연결 버튼이 있는 편집 창에 보여 줍니다.
                            val editorGuide = settingsGuide?.takeIf { it.step == BroadcastTutorialStep.CONNECT_ACCOUNT }
                            // 하위 설정 창이 열리면 메뉴를 숨기고, 닫으면 다시 메뉴로 돌아옵니다.
                            if (openSettingsDialog && !editorOpen) {
                                val notSelected = stringResource(R.string.broadcast_menu_not_selected)
                                val needsDetails = stringResource(R.string.broadcast_menu_needs_details)
                                val youtubeValid = validateYouTubeLiveSettings(props.broadcastSettings).isValid
                                val chzzkValid = chzzkSettings.validationField() == null
                                val chzzkStatus = chzzkAccountStatus(chzzkAccountVerification, chzzkMessage)
                                val modePlan = broadcastPlanState.plan
                                // 동의·안내를 순서대로 받은 뒤 카메라·마이크 권한을 확인하고 서버 연결을 시작합니다.
                                fun continueBroadcastPreparation() {
                                    val consentStep = nextBroadcastConsentStep(providers, broadcastConsentStore::hasAccepted)
                                    when {
                                        consentStep != null -> pendingConsentStep = consentStep
                                        readMediaPermissionState(context).missingPermissions.isNotEmpty() -> {
                                            mediaPermissions.refresh()
                                            mediaPermissionLauncher.launch(
                                                readMediaPermissionState(context).missingPermissions.toTypedArray(),
                                            )
                                        }
                                        when {
                                            providers.size > 1 -> webRtcSession.prepareSimulcastBroadcast(
                                                context, props.broadcastSettings, chzzkSettings, props.onRefreshAccessToken,
                                            )
                                            BroadcastProvider.CHZZK in providers -> webRtcSession.prepareChzzkBroadcast(
                                                context, chzzkSettings, props.onRefreshAccessToken,
                                            )
                                            else -> webRtcSession.prepareBroadcast(
                                                context, props.broadcastSettings, props.onRefreshAccessToken,
                                            )
                                        } -> {
                                            openSettingsDialog = false
                                            requestDirectBroadcastNotifications()
                                        }
                                    }
                                }
                                pendingConsentStep?.let { step ->
                                    BroadcastConsentDialog(
                                        step = step,
                                        onAccept = {
                                            // 기록에 실패하면 준비 요청 단계에서 차단하고 상태 문구로 알립니다.
                                            runCatching { broadcastConsentStore.record(step) }
                                            pendingConsentStep = null
                                            continueBroadcastPreparation()
                                        },
                                        onDismiss = { pendingConsentStep = null },
                                    )
                                }
                                val modeSummary = stringResource(modePlan.resolution.labelRes) + " · " +
                                    DirectProviderOrder.filter { it in modePlan.providers }.joinToString(" + ") { it.title }
                                BroadcastSettingsMenu(
                                    items = listOf(
                                        BroadcastSettingsMenuItem(
                                            title = BroadcastProvider.YOUTUBE.title,
                                            summary = when {
                                                BroadcastProvider.YOUTUBE !in providers -> notSelected
                                                !youtubeValid -> needsDetails
                                                else -> props.youtubeAccountStatus
                                            },
                                            onClick = { openYouTubeEditor = true },
                                            highlightsAccount = BroadcastProvider.YOUTUBE in providers && !youtubeReady,
                                            iconRes = BroadcastProvider.YOUTUBE.logoRes,
                                        ),
                                        BroadcastSettingsMenuItem(
                                            title = stringResource(R.string.broadcast_menu_chzzk),
                                            summary = when {
                                                BroadcastProvider.CHZZK !in providers -> notSelected
                                                !chzzkValid -> needsDetails
                                                else -> chzzkStatus
                                            },
                                            onClick = { openChzzkEditor = true },
                                            iconRes = BroadcastProvider.CHZZK.logoRes,
                                            highlightsAccount = BroadcastProvider.CHZZK in providers && !chzzkReady &&
                                                (BroadcastProvider.YOUTUBE !in providers || youtubeReady),
                                        ),
                                        BroadcastSettingsMenuItem(
                                            title = stringResource(R.string.broadcast_menu_mode),
                                            summary = modeSummary,
                                            onClick = { openModeEditor = true },
                                            iconVector = Icons.Outlined.Tune,
                                        ),
                                    ),
                                    canPrepare = (BroadcastProvider.YOUTUBE !in providers || youtubeReady) &&
                                        (BroadcastProvider.CHZZK !in providers || chzzkReady),
                                    onPrepare = {
                                        when {
                                            // 입력이 빠진 플랫폼은 그 설정 창을 열어 채우게 합니다.
                                            BroadcastProvider.YOUTUBE in providers && !youtubeValid -> openYouTubeEditor = true
                                            BroadcastProvider.CHZZK in providers && !chzzkValid -> openChzzkEditor = true
                                            else -> continueBroadcastPreparation()
                                        }
                                    },
                                    onDismiss = {
                                        openSettingsDialog = false
                                        webRtcSession.dismissServerError()
                                    },
                                    guide = settingsGuide,
                                )
                            }
                            if (openYouTubeEditor) {
                                // 편집 창에는 방송 준비 버튼이 없으므로 계정 연결 단계 안내만 보여 줍니다.
                                YouTubeLiveSettingsDialog(
                                    settings = props.broadcastSettings,
                                    guide = editorGuide,
                                    youtubeChannelTitle = props.youtubeChannelTitle,
                                    hasYouTubeAccount = props.hasYouTubeAccount,
                                    youtubeAccountStatus = props.youtubeAccountStatus,
                                    isYouTubeReconnectRequired = props.isYouTubeReconnectRequired,
                                    isYouTubeAccountActionInProgress = props.isYouTubeAccountActionInProgress,
                                    isYouTubeConnectEnabled = props.isYouTubeConnectEnabled,
                                    serverError = webRtcSession.serverError?.takeIf { it.action == ServerErrorAction.EDIT_SETTINGS },
                                    onSettingsChanged = {
                                        webRtcSession.dismissServerError()
                                        props.onBroadcastSettingsChanged(it)
                                    },
                                    onConnectYouTube = props.onConnectYouTube,
                                    onDismissRequest = { openYouTubeEditor = false },
                                )
                            }
                            if (openChzzkEditor) {
                                ChzzkSettingsDialog(
                                    settings = chzzkSettings,
                                    guide = editorGuide,
                                    accountLabel = chzzkAccountStatus(chzzkAccountVerification, chzzkMessage),
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
                                    canPrepare = chzzkAccountVerification.canPrepare,
                                    onPrepare = null,
                                    onDismiss = { openChzzkEditor = false },
                                )
                            }
                            if (openModeEditor) {
                                BroadcastModeDialog(
                                    state = broadcastPlanState,
                                    onPlanChanged = { plan ->
                                        if (webRtcSession.selectDirectBroadcastPlan(plan)) {
                                            directModeUserSelected = true
                                            webRtcSession.selectProviders(plan.providers)
                                            selectedPlatform = platformPreferenceOf(plan.providers)
                                        }
                                    },
                                    onOpenPlan = {
                                        openModeEditor = false
                                        openSettingsDialog = false
                                        props.onOpenPlan()
                                    },
                                    onDismiss = { openModeEditor = false },
                                    // 조회가 실제로 진행 중일 때만 확인 중입니다. 조회 실패·로그인 필요는 그 사유를 보여 줍니다.
                                    accountChecking = { provider ->
                                        provider == BroadcastProvider.CHZZK && (chzzkBusy || chzzkAccountVerification.mutationInProgress)
                                    },
                                    accountIssue = { provider ->
                                        chzzkMessage.takeIf { provider == BroadcastProvider.CHZZK && !chzzkAccountVerification.verified }
                                    },
                                    // 계정이 없는 플랫폼은 해당 설정 창에서 연결합니다.
                                    onConnectAccount = { provider ->
                                        openModeEditor = false
                                        if (provider == BroadcastProvider.YOUTUBE) openYouTubeEditor = true else openChzzkEditor = true
                                    },
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
                            if (openBroadcastActions) {
                                val retryProviders = webRtcSession.simulcastRetryProviders
                                BroadcastActionDialog(
                                    presentation = presentation,
                                    canRetryPreparation = retryProviders.isNotEmpty(),
                                    onRetryPreparation = {
                                        openBroadcastActions = false
                                        // 남은 플랫폼의 설정부터 다시 엽니다. 준비 요청은 준비된 플랫폼을 건너뜁니다.
                                        openBroadcastSettings(retryProviders.first())
                                    },
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
                }
                // 가로에서는 세로 하단 바를 기기 오른쪽 끝으로 옮깁니다. 기기를 돌렸을 때 세로 하단이 놓이는 자리입니다.
                // 오른손 엄지가 닿는 가운데에 방송 버튼을 두고, 익명화와 카메라 전환을 위아래 끝에 둡니다.
                if (landscape) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .onSizeChanged { pipState.rightControlsWidthPx = it.width.toFloat() }
                            .padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                            .widthIn(min = 72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        anonymizationButton()
                        broadcastControls(Modifier)
                        switchCameraButton()
                    }
                } else {
                    SideEffect { pipState.rightControlsWidthPx = 0f }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 작은 창과 카메라 도구가 이 높이 위에만 놓이도록 실제 하단 영역 높이를 알려 줍니다.
                        .onSizeChanged { pipState.bottomControlsHeightPx = it.height.toFloat() }
                        .then(if (landscape) Modifier.widthIn(max = 420.dp) else Modifier)
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
                    // Figma 기본 화면처럼 카메라 전환·방송 버튼·익명화를 한 줄에 둡니다. 가로에서는 오른쪽 열로 옮깁니다.
                    if (!landscape) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            switchCameraButton()
                            broadcastControls(Modifier.weight(1f))
                            anonymizationButton()
                        }
                    }
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
                    if (shouldShowNotificationsHiddenNotice(
                            notificationsHidden = broadcastNotificationPermission.notificationsHidden,
                            directBroadcast = webRtcSession.selectedOnDeviceProcessing && DirectPublishFeature.ENABLED,
                            state = webRtcSession.broadcastState,
                        )) {
                            Text(
                                text = stringResource(broadcastNotificationPermission.hiddenMessage),
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
            },
        )

        if (tutorial != null) {
            BroadcastTutorialHomeOverlay(
                tutorial = tutorial,
                anchors = tutorialAnchors,
                isEnabled = canShowTutorial,
            )
        }
    }
    if (openFaceManagement && faceManagementUsesBroadcastCamera) {
        // iOS처럼 방송 화면과 카메라는 그대로 두고, 송출 중인 카메라 프레임으로 얼굴을 등록합니다.
        Dialog(
            onDismissRequest = { openFaceManagement = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LiveFaceManagement(
                props = props,
                webRtcSession = webRtcSession,
                broadcastCamera = frameAnalyzer,
                onClose = { openFaceManagement = false },
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
                        openBroadcastSettings(BroadcastProvider.YOUTUBE)
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

/** 방송 카메라 프레임을 계속 받을 수 있는 방송 단계입니다. 전환 중에는 카메라가 바뀔 수 있어 제외합니다. */
private val FaceManagementBroadcastStates = setOf(
    BroadcastState.IDLE,
    BroadcastState.PREPARED,
    BroadcastState.LIVE,
    BroadcastState.PAUSED,
)

/** 원탭 얼굴 선택이 가능한 방송 단계입니다. 실패 뒤에도 카메라는 켜져 있으므로 포함합니다. */
private val FaceSelectionBroadcastStates = FaceManagementBroadcastStates + BroadcastState.FAILED

@Composable
private fun LiveFaceManagement(
    props: LiveScreenProps,
    webRtcSession: WebRtcSessionViewModel,
    broadcastCamera: CameraFrameAnalyzer?,
    onClose: () -> Unit,
) {
    if (webRtcSession.selectedOnDeviceProcessing) {
        LocalFaceManagementScreen(
            cameraLensFacing = props.cameraLensFacing,
            accountScope = props.onGetAccessToken()?.let { token ->
                runCatching { sessionRecoveryScope(BuildConfig.INNOLIVE_SERVER_URL, token).storageKey }.getOrNull()
            },
            onBack = { onClose(); webRtcSession.localFacesChanged() },
            onChanged = webRtcSession::localFacesChanged,
            broadcastCamera = broadcastCamera,
        )
    } else {
        FaceManagementScreen(
            cameraLensFacing = props.cameraLensFacing,
            onGetAccessToken = props.onGetAccessToken,
            onRefreshAccessToken = props.onRefreshAccessToken,
            onBack = onClose,
            profileEmail = props.profileEmail,
            broadcastCamera = broadcastCamera,
        )
    }
}

/**
 * 기본 화면의 카메라 도구입니다. 미리보기를 가리지 않도록 오른쪽 위에 세로로 두고,
 * 아이콘 아래에 이름을 함께 보여 줍니다.
 */
@Composable
internal fun LiveCameraTools(
    modifier: Modifier = Modifier,
    canManageFace: Boolean,
    onOpenVideoControls: () -> Unit,
    onOpenFaceManagement: () -> Unit,
    canSelectFace: Boolean = false,
    isSelectingFace: Boolean = false,
    onToggleFaceSelection: () -> Unit = {},
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LiveCameraToolButton(
            label = stringResource(R.string.video_controls_title),
            onClick = onOpenVideoControls,
        ) { Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(22.dp)) }
        LiveCameraToolButton(
            label = stringResource(R.string.face_management),
            onClick = onOpenFaceManagement,
            enabled = canManageFace,
        ) { Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(24.dp)) }
        // 홈 화면 원탭 등록 토글입니다. 선택 중에는 취소 버튼이 됩니다.
        LiveCameraToolButton(
            label = stringResource(
                if (isSelectingFace) R.string.quick_face_select_cancel else R.string.quick_face_select_start,
            ),
            onClick = onToggleFaceSelection,
            enabled = isSelectingFace || canSelectFace,
        ) {
            Icon(
                imageVector = if (isSelectingFace) Icons.Default.Close else Icons.Default.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 영상 위에 놓이는 보조 도구라 흰 원 대신 반투명한 어두운 원으로 가볍게 두고, 흰 바탕은 방송 버튼에만 씁니다.
 * 아이콘과 이름을 한 덩어리로 누릅니다.
 */
@Composable
private fun LiveCameraToolButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.45f)
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp, max = 96.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = contentColor,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        // 밝은 장면 위에서도 읽히도록 이름 뒤에 반투명한 어두운 바탕을 깝니다.
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 밝은 장면에서도 흰 글자가 보이도록 Figma의 1px 그림자를 줍니다. */
private val LiveLabelShadow = Shadow(color = Color.Black.copy(alpha = 0.35f), offset = Offset(1f, 1f), blurRadius = 2f)

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

/** 준비 마감 안내처럼 초 단위로 다시 그릴 때만 시계를 돌립니다. */
@Composable
private fun rememberElapsedRealtime(ticking: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(ticking) {
        now = SystemClock.elapsedRealtime()
        while (ticking) {
            delay(1_000L - now % 1_000L)
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
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
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
    /** 가로 화면의 오른쪽 열에 세로로 긴 버튼으로 놓을 때입니다. */
    vertical: Boolean = false,
    centerOverlay: @Composable () -> Unit = {},
) {
    Box(
        modifier = if (vertical) modifier else modifier.fillMaxWidth().padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        centerOverlay()
        if (vertical) {
            // 가로 화면은 카메라 셔터처럼 엄지가 닿는 둥근 버튼을 두고, 상태 이름은 그 아래에 바로 읽히게 적습니다.
            LandscapeHeroButton(
                text = stringResource(presentation.broadcastButtonTextRes),
                enabled = presentation.isBroadcastButtonEnabled,
                live = presentation.isBroadcastLive,
                onClick = onBroadcastAction,
                buttonModifier = buttonModifier,
            )
        } else {
            VerticalHeroButton(
                text = stringResource(presentation.broadcastButtonTextRes),
                enabled = presentation.isBroadcastButtonEnabled,
                onClick = onBroadcastAction,
                modifier = buttonModifier,
            )
        }
    }
}


@Composable
private fun LandscapeHeroButton(
    text: String,
    enabled: Boolean,
    live: Boolean,
    onClick: () -> Unit,
    buttonModifier: Modifier,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 112.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = buttonModifier.size(68.dp),
            shape = CircleShape,
            // 방송 중에는 빨간 버튼으로 바꿔 지금 송출 중임을 멀리서도 알게 합니다.
            color = when {
                !enabled -> Color(0xFF8A8A8A)
                live -> Color(0xFFE53935)
                else -> Color.White
            },
            contentColor = if (live) Color.White else Color.Black,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Sensors, contentDescription = null, modifier = Modifier.size(30.dp))
            }
        }
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.copy(shadow = LiveLabelShadow),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
internal fun BroadcastActionDialog(
    presentation: LiveScreenPresentation,
    canRetryPreparation: Boolean = false,
    onRetryPreparation: () -> Unit = {},
    onDismiss: () -> Unit,
    onGoLive: () -> Unit,
    onCancelPreparation: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
) {
    InnoLiveDialog(onDismissRequest = onDismiss) {
        ModalSurface(modifier = Modifier.cappedDialogWidth(400.dp)) {
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
                    if (canRetryPreparation) {
                        BroadcastDialogButton(
                            stringResource(R.string.simulcast_action_retry_remaining),
                            onRetryPreparation,
                            enabled = presentation.canStartOrResumeBroadcast,
                        )
                    }
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

private val SimulcastProviders = setOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK)

/** 기존 저장값(`YouTube`·`CHZZK`·`SIMULCAST`)과 같은 형식으로 플랫폼 선택을 저장합니다. */
internal fun platformPreferenceOf(providers: Set<BroadcastProvider>): String = when {
    providers.size > 1 -> "SIMULCAST"
    BroadcastProvider.CHZZK in providers -> "CHZZK"
    else -> "YouTube"
}

/** 치지직 계정 상태를 메뉴와 치지직 설정 창에 같은 문구로 보여 줍니다. */
@Composable
private fun chzzkAccountStatus(chzzkAccountVerification: ChzzkAccountVerification, chzzkMessage: UiText?): String =
    when {
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
    }
