package com.framework.innolive.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.framework.innolive.R
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.AudioInputDevice
import com.framework.innolive.feature.live.BroadcastVideoQualitySettings
import com.framework.innolive.feature.live.BroadcastVideoQualityPreferences
import com.framework.innolive.feature.live.VideoQualityCaptureState
import com.framework.innolive.feature.live.VideoStabilizationStatus
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.CameraResolution
import com.framework.innolive.feature.live.LiveScreen
import com.framework.innolive.feature.live.LiveScreenProps
import com.framework.innolive.feature.live.LiveSettingsPage
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.WebRtcSessionViewModel
import com.framework.innolive.feature.live.broadcastSettingsSaveDisabledReason
import com.framework.innolive.feature.live.rememberAudioInputDevices
import com.framework.innolive.feature.live.supportedCameraResolutions
import com.framework.innolive.feature.live.consent.BroadcastConsentStep
import com.framework.innolive.feature.live.consent.BroadcastConsentStore
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialCoordinator
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialViewModel
import com.framework.innolive.feature.login.LoginScreen
import com.framework.innolive.feature.login.LoginScreenProps
import com.framework.innolive.feature.login.oauth.google.AuthenticationSessionViewModel
import com.framework.innolive.feature.login.oauth.google.SignInMethod
import com.framework.innolive.feature.live.recording.BroadcastRecordingLibraryScreen
import com.framework.innolive.feature.live.recording.BroadcastRecordingStatus
import com.framework.innolive.feature.settings.AccountSettingsScreen
import com.framework.innolive.feature.settings.SettingsScreen
import com.framework.innolive.feature.settings.SettingsScreenProps
import com.framework.innolive.feature.settings.PlanUsage
import com.framework.innolive.feature.settings.PlanMode
import com.framework.innolive.feature.settings.rememberPlanChargeMillis
import com.framework.innolive.feature.live.sessionRecoveryScope
import com.framework.innolive.feature.youtube.YouTubeApiException
import com.framework.innolive.feature.settings.broadcast.BroadcastSetting
import com.framework.innolive.feature.settings.broadcast.BroadcastSettingProps
import com.framework.innolive.feature.settings.selection.OptionSelectionScreen
import com.framework.innolive.feature.settings.selection.SettingOption
import com.framework.innolive.feature.youtube.OperationGeneration
import com.framework.innolive.feature.youtube.StreamingAccount
import com.framework.innolive.feature.youtube.YouTubeAccountCoordinator
import com.framework.innolive.feature.youtube.YouTubeAccountVerificationState
import com.framework.innolive.feature.youtube.YouTubePreferencesStore
import com.framework.innolive.feature.youtube.acceptServerVerifiedYouTubeAccount
import com.framework.innolive.feature.youtube.cancelYouTubeAuthorization
import com.framework.innolive.feature.youtube.canChangeYouTubeAccount
import com.framework.innolive.feature.youtube.defaultYouTubeBroadcastTitle
import com.framework.innolive.feature.youtube.hasVerifiedYouTubeAccount
import com.framework.innolive.feature.youtube.rememberYouTubeVerificationMemory
import com.framework.innolive.feature.live.components.ServerErrorDialog
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.feature.youtube.youtubeFailureGuidance
import com.framework.innolive.feature.youtube.youtubeConnectionFailureMessage
import com.framework.innolive.feature.youtube.youtubeFailureDiagnostic
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.UiTextSaver
import com.framework.innolive.ui.text.NullableUiTextSaver
import com.framework.innolive.ui.text.asString
import com.framework.innolive.ui.theme.MyApplicationTheme
import java.io.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

sealed interface AppRoute : Serializable
data object LoginRoute : AppRoute

data object LiveRoute : AppRoute

data object SettingsRoute : AppRoute

data object AccountSettingsRoute : AppRoute

data object BroadcastSettingRoute : AppRoute

data object RecordingsRoute : AppRoute

/** 보관함에 들어갈 수 있는 방송 상태입니다. 준비·라이브·일시 중지·전환 중에는 막습니다. */
private val RECORDINGS_ALLOWED_STATES = setOf(BroadcastState.IDLE, BroadcastState.FAILED)

enum class SettingOptionType {
    CAMERA_RESOLUTION,
    CAMERA_DEVICE,
    AUDIO_DEVICE,
    BROADCAST_PLATFORM,
    BROADCAST_PRIVACY,
    BROADCAST_AUDIENCE,
}

data class SettingOptionRoute(
    val type: SettingOptionType,
) : AppRoute

private data class OptionSelectionConfig(
    val title: UiText,
    val options: List<SettingOption>,
    val selectedKey: String,
    val onOptionSelected: (String) -> Unit,
)

private val broadcastPlatformOptions = listOf(
    "YouTube",
)

private val broadcastPrivacyOptions = listOf(
    SettingOption(key = "public", label = UiText.Resource(R.string.privacy_public)),
    SettingOption(key = "unlisted", label = UiText.Resource(R.string.privacy_unlisted)),
    SettingOption(key = "private", label = UiText.Resource(R.string.privacy_private)),
)

private val broadcastAudienceOptions = listOf(
    SettingOption(key = "unset", label = UiText.Resource(R.string.audience_required)),
    SettingOption(key = "true", label = UiText.Resource(R.string.audience_made_for_kids)),
    SettingOption(key = "false", label = UiText.Resource(R.string.audience_not_made_for_kids)),
)

private fun CameraLensFacing.settingDisplayText(): UiText = when (this) {
    CameraLensFacing.BACK -> UiText.Resource(R.string.camera_back)
    CameraLensFacing.FRONT -> UiText.Resource(R.string.camera_front)
}

// 내장 마이크의 제품명은 기기 모델명이라 기본 마이크로만 보여 주고, 외부 마이크는 기기 이름을 그대로 보여 줍니다.
private fun AudioInputDevice.settingDisplayText(): UiText = if (isDefault) {
    UiText.Resource(R.string.audio_device_default)
} else {
    UiText.Dynamic(name)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val webRtcSession = ViewModelProvider(this)[WebRtcSessionViewModel::class.java]
        val authenticationSession =
            ViewModelProvider(this)[AuthenticationSessionViewModel::class.java]
        val broadcastTutorial = ViewModelProvider(this)[BroadcastTutorialViewModel::class.java].coordinator
        setContent {
            LaunchedEffect(webRtcSession.lockedScreenOrientation) {
                val lockedOrientation = webRtcSession.lockedScreenOrientation
                if (lockedOrientation != null) {
                    requestedOrientation = lockedOrientation
                } else if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    AppNavigation(
                        webRtcSession = webRtcSession,
                        authenticationSession = authenticationSession,
                        broadcastTutorial = broadcastTutorial,
                        modifier = Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .background(color = MaterialTheme.colorScheme.background),
                    )
                }
            }
        }
    }
}

@Composable
fun AppNavigation(
    webRtcSession: WebRtcSessionViewModel,
    authenticationSession: AuthenticationSessionViewModel,
    broadcastTutorial: BroadcastTutorialCoordinator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val coroutineScope = rememberCoroutineScope()
    val session by authenticationSession.session.collectAsStateWithLifecycle()
    val googleSignInState by authenticationSession.googleSignInState.collectAsStateWithLifecycle()
    val accountDeletionState by authenticationSession.accountDeletionState.collectAsStateWithLifecycle()
    val isDeletingAccount = accountDeletionState.isInProgress
    val isAccountDeletionPending = accountDeletionState.hasPendingDeletion
    val youtubeCoordinator = remember(activity) { YouTubeAccountCoordinator(activity) }
    val youtubePreferencesStore = remember(context) { YouTubePreferencesStore(context) }
    val broadcastConsentStore = remember(context) { BroadcastConsentStore(context) }
    val videoQualityPreferences = remember(context) { BroadcastVideoQualityPreferences(context) }
    var videoQualitySettings by remember(videoQualityPreferences) { mutableStateOf(videoQualityPreferences.load()) }
    var videoQualityCaptureState by remember { mutableStateOf(VideoQualityCaptureState()) }
    val updateVideoQualitySettings: (BroadcastVideoQualitySettings) -> Unit = { value ->
        val normalized = value.normalized()
        if (videoQualitySettings.stabilizationEnabled != normalized.stabilizationEnabled) {
            videoQualityCaptureState = videoQualityCaptureState.copy(stabilizationStatus = VideoStabilizationStatus.PENDING)
        }
        videoQualitySettings = normalized
        videoQualityPreferences.save(normalized)
    }
    val restoredYouTubeAccount = remember(youtubePreferencesStore) {
        youtubePreferencesStore.loadConnection()
    }
    val restoredBroadcastSettings = remember(youtubePreferencesStore) {
        youtubePreferencesStore.loadBroadcastSettings()
    }
    var youtubeAccountProvider by rememberSaveable {
        mutableStateOf(restoredYouTubeAccount?.provider)
    }
    var youtubeAccountChannelId by rememberSaveable {
        mutableStateOf(restoredYouTubeAccount?.channelId)
    }
    var youtubeAccountChannelTitle by rememberSaveable {
        mutableStateOf(restoredYouTubeAccount?.channelTitle)
    }
    var youtubeAccountReconnectRequired by rememberSaveable {
        mutableStateOf(restoredYouTubeAccount?.reconnectRequired == true)
    }
    val youtubeAccount = youtubeAccountProvider?.let { provider ->
        StreamingAccount(
            provider = provider,
            channelId = youtubeAccountChannelId.orEmpty(),
            channelTitle = youtubeAccountChannelTitle.orEmpty(),
            reconnectRequired = youtubeAccountReconnectRequired,
        )
    }
    var youtubeAccountGuidance by remember { mutableStateOf<ServerErrorGuidance?>(null) }
    var youtubeAccountStatus by rememberSaveable(stateSaver = UiTextSaver) {
        mutableStateOf<UiText>(
            if (restoredYouTubeAccount == null) {
                UiText.Resource(R.string.youtube_status_sign_in_required)
            } else {
                UiText.Resource(R.string.youtube_status_checking_saved)
            },
        )
    }
    var isYouTubeAccountActionInProgress by rememberSaveable { mutableStateOf(false) }
    val youtubeVerification = rememberYouTubeVerificationMemory()
    var youtubeAccountVerificationState by youtubeVerification.state
    var verifiedYouTubeProfileEmail by youtubeVerification.verifiedProfileEmail
    var youtubeOperationProfileEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var previousProfileEmail by rememberSaveable { mutableStateOf(session?.profileEmail) }
    var isYouTubeAuthorizationLaunched by rememberSaveable { mutableStateOf(false) }
    var youtubeAuthorizationOperation by rememberSaveable { mutableStateOf<Long?>(null) }
    var youtubeAccountStatusBeforeAuthorization by rememberSaveable(
        stateSaver = NullableUiTextSaver,
    ) {
        mutableStateOf<UiText?>(null)
    }
    var suppressYouTubeAccountRefreshOnce by youtubeVerification.suppressRefreshOnce
    val youtubeOperationGeneration = rememberSaveable(
        saver = Saver<OperationGeneration, Long>(
            save = { generation -> generation.current },
            restore = { value -> OperationGeneration(value) },
        ),
    ) { OperationGeneration() }

    DisposableEffect(youtubeCoordinator) {
        onDispose { youtubeCoordinator.close() }
    }

    LaunchedEffect(Unit) {
        if (isYouTubeAccountActionInProgress && !isYouTubeAuthorizationLaunched) {
            youtubeAuthorizationOperation = null
            youtubeAccountStatusBeforeAuthorization = null
            youtubeOperationGeneration.invalidate()
            isYouTubeAccountActionInProgress = false
            youtubeAccountStatus = UiText.Resource(R.string.youtube_status_retry_connect)
        }
    }

    suspend fun refreshCurrentAccessToken(): String {
        return authenticationSession.refreshAccessToken()
    }

    fun updateYouTubeAccount(account: StreamingAccount?) {
        youtubeAccountProvider = account?.provider
        youtubeAccountChannelId = account?.channelId
        youtubeAccountChannelTitle = account?.channelTitle
        youtubeAccountReconnectRequired = account?.reconnectRequired == true
        youtubeAccountStatus = when {
            account == null -> UiText.Resource(R.string.youtube_status_no_account)
            account.reconnectRequired -> UiText.Resource(R.string.youtube_status_reconnect_required)
            account.channelTitle.isNotBlank() -> UiText.Resource(
                R.string.youtube_status_channel,
                listOf(account.channelTitle),
            )
            else -> UiText.Resource(R.string.youtube_status_connected)
        }
    }

    fun updateVerifiedYouTubeAccount(account: StreamingAccount?) {
        val cacheUpdated = acceptServerVerifiedYouTubeAccount(
            account = account,
            onVerified = { verifiedAccount ->
                updateYouTubeAccount(verifiedAccount)
                verifiedYouTubeProfileEmail = session?.profileEmail
                youtubeAccountVerificationState = YouTubeAccountVerificationState.VERIFIED
            },
            saveConnection = youtubePreferencesStore::saveConnection,
            removeConnection = {
                youtubePreferencesStore.removeConnection()
                // 서버에서 연결이 없음을 확인하면 다음 연결 후 방송 준비에서 전송 안내를 다시 받습니다.
                broadcastConsentStore.clear(BroadcastConsentStep.YOUTUBE_TRANSMISSION)
            },
        )
        if (!cacheUpdated) {
            Log.w("InnoLiveYouTube", "Unable to update YouTube account display cache.")
        }
    }

    fun isCurrentYouTubeOperation(operation: Long): Boolean =
        youtubeOperationGeneration.isCurrent(operation) &&
            session != null && youtubeOperationProfileEmail == session?.profileEmail

    fun showYouTubeAccountFailure(
        operation: Long,
        exception: Throwable? = null,
        stage: String = "connection_completion",
        suppressAccountRefresh: Boolean = true,
    ) {
        if (!isCurrentYouTubeOperation(operation)) return
        Log.w("InnoLiveYouTube", "failure_stage=$stage ${youtubeFailureDiagnostic(exception)}")
        youtubeAuthorizationOperation = null
        isYouTubeAuthorizationLaunched = false
        suppressYouTubeAccountRefreshOnce = suppressAccountRefresh
        isYouTubeAccountActionInProgress = false
        youtubeAccountStatusBeforeAuthorization = null
        youtubeAccountStatus = youtubeConnectionFailureMessage(exception)
        youtubeAccountGuidance = youtubeFailureGuidance(exception)
    }

    fun canChangeYouTubeAccountNow(): Boolean = canChangeYouTubeAccount(
        hasSignedInUser = session != null,
        broadcastState = webRtcSession.broadcastState,
        isPreparingBroadcast = webRtcSession.isPreparingBroadcast,
        isDeletingAccount = isDeletingAccount,
        isAccountDeletionPending = isAccountDeletionPending,
    )

    fun cancelYouTubeAccountChange(operation: Long) {
        if (!isCurrentYouTubeOperation(operation)) return
        youtubeOperationGeneration.invalidate()
        youtubeAuthorizationOperation = null
        isYouTubeAuthorizationLaunched = false
        val cancellationState = cancelYouTubeAuthorization(
            accountStatusBeforeAuthorization =
                youtubeAccountStatusBeforeAuthorization ?: youtubeAccountStatus,
            verificationState = youtubeAccountVerificationState,
        )
        youtubeAccountStatus = cancellationState.accountStatus
        youtubeAccountVerificationState = cancellationState.verificationState
        suppressYouTubeAccountRefreshOnce = !cancellationState.shouldRefreshAccount
        isYouTubeAccountActionInProgress = cancellationState.isActionInProgress
        youtubeAccountStatusBeforeAuthorization = null
    }

    fun completeYouTubeConnection(operation: Long, serverAuthCode: String) {
        if (!isCurrentYouTubeOperation(operation) || !isYouTubeAccountActionInProgress) return
        if (!canChangeYouTubeAccountNow()) {
            cancelYouTubeAccountChange(operation)
            return
        }
        youtubeAuthorizationOperation = null
        isYouTubeAuthorizationLaunched = false
        coroutineScope.launch {
            try {
                suspend fun refreshConnectionAccessToken(): String {
                    val accessToken = refreshCurrentAccessToken()
                    if (!isCurrentYouTubeOperation(operation)) {
                        throw CancellationException("YouTube account operation is no longer current.")
                    }
                    if (!canChangeYouTubeAccountNow()) {
                        cancelYouTubeAccountChange(operation)
                        throw CancellationException("YouTube account change is no longer allowed.")
                    }
                    return accessToken
                }
                val accessToken = refreshConnectionAccessToken()
                youtubeVerification.connectAccount {
                    val account = youtubeCoordinator.connect(
                        serverAuthCode = serverAuthCode,
                        accessToken = accessToken,
                        refreshAccessToken = ::refreshConnectionAccessToken,
                    )
                    if (isCurrentYouTubeOperation(operation)) updateVerifiedYouTubeAccount(account)
                    youtubeCoordinator.removeOtherYouTubeAccounts(
                        connected = account,
                        accessToken = accessToken,
                        refreshAccessToken = ::refreshConnectionAccessToken,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showYouTubeAccountFailure(
                    operation,
                    exception,
                    suppressAccountRefresh =
                        youtubeAccountVerificationState == YouTubeAccountVerificationState.VERIFIED,
                )
            } finally {
                if (isCurrentYouTubeOperation(operation)) {
                    youtubeAuthorizationOperation = null
                    isYouTubeAuthorizationLaunched = false
                    isYouTubeAccountActionInProgress = false
                    youtubeAccountStatusBeforeAuthorization = null
                }
            }
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val operation = youtubeAuthorizationOperation ?: return@rememberLauncherForActivityResult
        youtubeAuthorizationOperation = null
        if (isCurrentYouTubeOperation(operation) && isYouTubeAccountActionInProgress) {
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                isYouTubeAuthorizationLaunched = false
                runCatching { youtubeCoordinator.serverAuthCodeFromIntent(data) }
                    .onSuccess { serverAuthCode ->
                        completeYouTubeConnection(operation, serverAuthCode)
                    }
                    .onFailure { exception -> showYouTubeAccountFailure(operation, exception, "google_result") }
            } else {
                cancelYouTubeAccountChange(operation)
            }
        }
    }
    val packageManager = context.packageManager
    val cameraDeviceOptions = remember(packageManager) {
        CameraLensFacing.supported(
            hasBackCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA),
            hasFrontCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT),
        )
    }
    val audioInputDevices = rememberAudioInputDevices(context)
    val audioDeviceOptions = remember(audioInputDevices) {
        audioInputDevices
            .map { device ->
                AudioInputDevice(
                    id = device.id,
                    name = device.productName.toString(),
                    isDefault = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
                )
            }
    }
    val backStack = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toCollection(mutableStateListOf()) },
        ),
    ) {
        mutableStateListOf<AppRoute>(
            when {
                session == null -> LoginRoute
                isAccountDeletionPending -> SettingsRoute
                else -> LiveRoute
            },
        )
    }
    val planAccount = session?.let {
        runCatching { sessionRecoveryScope(BuildConfig.INNOLIVE_SERVER_URL, it.accessToken).storageKey }
            .getOrNull()
    }
    var planUsage by remember(planAccount) { mutableStateOf<PlanUsage?>(null) }
    var planError by remember(planAccount) { mutableStateOf<UiText?>(null) }
    var isPlanLoading by remember(planAccount) { mutableStateOf(false) }
    var planRefresh by remember(planAccount) { mutableIntStateOf(0) }
    var planUsageChargeMillis by remember(planAccount) { mutableLongStateOf(0L) }
    // 관리자 전용 실험실 표시 여부입니다. 확인하지 못하면 숨기고 다음 화면 진입에서 다시 확인합니다.
    var isAdministrator by remember(planAccount) { mutableStateOf(false) }
    var isAdministratorChecked by remember(planAccount) { mutableStateOf(false) }
    LaunchedEffect(planAccount, backStack.lastOrNull()) {
        if (planAccount == null || isAdministratorChecked ||
            backStack.lastOrNull() !in setOf(LiveRoute, SettingsRoute)) return@LaunchedEffect
        try {
            val token = checkNotNull(authenticationSession.session.value).accessToken
            isAdministrator = youtubeCoordinator.isAdministrator(token, ::refreshCurrentAccessToken)
            isAdministratorChecked = true
            // 관리자가 아닌 계정은 합성을 요청할 수 없으므로 이전 계정이 켠 선택을 지웁니다.
            if (!isAdministrator) webRtcSession.clearFaceSwapSelection(context)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            isAdministrator = false
        }
    }
    val isBroadcastActive = webRtcSession.broadcastStartedAtElapsedRealtimeMillis != null
    // iOS처럼 방송 준비·방송 중에는 저장된 방송(보관함)에 들어가지 않습니다. 종료·실패 뒤 저장 중에는 허용합니다.
    val canOpenRecordings = !isBroadcastActive && !webRtcSession.isPreparingBroadcast &&
        webRtcSession.broadcastState in RECORDINGS_ALLOWED_STATES
    LaunchedEffect(canOpenRecordings) {
        if (!canOpenRecordings) backStack.removeAll { it == RecordingsRoute }
    }
    val planMode = PlanMode.current(webRtcSession.sessionSnapshot?.broadcastResolution,
        webRtcSession.sessionSnapshot?.visibleTargets?.size ?: 1)
    val planChargeMillis = rememberPlanChargeMillis(
        account = planAccount,
        charging = isBroadcastActive && webRtcSession.broadcastState == BroadcastState.LIVE,
        multiplier = planUsage?.availability(planMode)?.multiplier ?: 0L,
    )
    val currentPlanChargeMillis by rememberUpdatedState(planChargeMillis)
    val displayedPlanUsage = planUsage?.afterChargedSeconds(
        (planChargeMillis - planUsageChargeMillis).coerceAtLeast(0L) / 1_000L)
    LaunchedEffect(planAccount, backStack.lastOrNull(), planRefresh, isBroadcastActive) {
        if (planAccount == null || backStack.lastOrNull() !in setOf(LiveRoute, SettingsRoute)) return@LaunchedEffect
        if (backStack.lastOrNull() == LiveRoute && isBroadcastActive) return@LaunchedEffect
        do {
            isPlanLoading = true
            planError = null
            try {
                val token = checkNotNull(authenticationSession.session.value).accessToken
                val snapshot = youtubeCoordinator.loadPlanUsage(token, ::refreshCurrentAccessToken)
                coroutineContext.ensureActive()
                planUsage = snapshot
                planUsageChargeMillis = currentPlanChargeMillis
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                coroutineContext.ensureActive()
                planError = UiText.Resource(R.string.plan_load_failed)
                if ((exception as? YouTubeApiException)?.statusCode == 401) {
                    webRtcSession.close()
                    authenticationSession.clear()
                }
            } finally {
                isPlanLoading = false
            }
            if (backStack.lastOrNull() != SettingsRoute || !isBroadcastActive) break
            delay(15_000L)
        } while (true)
    }
    LaunchedEffect(
        backStack.lastOrNull(),
        session?.profileEmail,
        previousProfileEmail,
        isYouTubeAccountActionInProgress,
        isDeletingAccount,
    ) {
        if (isYouTubeAccountActionInProgress || isDeletingAccount) return@LaunchedEffect
        if (suppressYouTubeAccountRefreshOnce) {
            suppressYouTubeAccountRefreshOnce = false
            return@LaunchedEffect
        }

        val operation = youtubeOperationGeneration.begin()
        youtubeOperationProfileEmail = session?.profileEmail
        // 설정 화면으로 옮겨도 조회를 이어 갑니다. 화면 전환으로 취소된 조회가 확인 중 상태로 남지 않게 합니다.
        if (backStack.lastOrNull() in YouTubeAccountCheckRoutes && session != null) {
            youtubeAccountVerificationState = YouTubeAccountVerificationState.CHECKING
            youtubeAccountStatus = UiText.Resource(R.string.youtube_status_checking)
            try {
                val account = youtubeCoordinator.loadAccount(::refreshCurrentAccessToken)
                if (isCurrentYouTubeOperation(operation)) updateVerifiedYouTubeAccount(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (isCurrentYouTubeOperation(operation)) {
                    youtubeAccountVerificationState = YouTubeAccountVerificationState.UNVERIFIED
                    val guidance = youtubeFailureGuidance(exception)
                    youtubeAccountStatus = guidance?.message ?: UiText.Resource(R.string.youtube_status_check_failed)
                    youtubeAccountGuidance = guidance
                }
            }
        }
    }
    var selectedResolutionKey by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var selectedCameraLensFacing by rememberSaveable {
        mutableStateOf(cameraDeviceOptions.firstOrNull() ?: CameraLensFacing.BACK)
    }
    var supportedCameraResolutions by remember {
        mutableStateOf(emptyList<CameraResolution>())
    }
    var selectedAudioDeviceId by rememberSaveable {
        mutableIntStateOf(audioDeviceOptions.firstOrNull()?.id ?: -1)
    }
    LaunchedEffect(audioInputDevices, selectedAudioDeviceId) {
        val availableAudioDeviceId = audioInputDevices
            .firstOrNull { device -> device.id == selectedAudioDeviceId }
            ?.id
            ?: audioInputDevices.firstOrNull()?.id
            ?: -1
        if (selectedAudioDeviceId != availableAudioDeviceId) {
            selectedAudioDeviceId = availableAudioDeviceId
        }
    }
    var selectedBroadcastPlatform by rememberSaveable {
        mutableStateOf(broadcastPlatformOptions.first())
    }
    var broadcastTitle by rememberSaveable { mutableStateOf(restoredBroadcastSettings.title) }
    var isBroadcastTitleGeneratedDefault by rememberSaveable {
        mutableStateOf(!youtubePreferencesStore.hasSavedBroadcastTitle())
    }
    val displayedBroadcastTitle = if (isBroadcastTitleGeneratedDefault) {
        defaultYouTubeBroadcastTitle(context)
    } else {
        broadcastTitle
    }
    var broadcastDescription by rememberSaveable {
        mutableStateOf(restoredBroadcastSettings.description)
    }
    var broadcastPrivacy by rememberSaveable { mutableStateOf(restoredBroadcastSettings.privacy) }
    var broadcastAudience by rememberSaveable {
        mutableStateOf(
            when (restoredBroadcastSettings.madeForKids) {
                true -> "true"
                false -> "false"
                null -> "unset"
            },
        )
    }
    var broadcastCategoryId by rememberSaveable {
        mutableStateOf(restoredBroadcastSettings.categoryId)
    }
    LaunchedEffect(session?.profileEmail) {
        if (session == null || previousProfileEmail != session?.profileEmail) {
            youtubeOperationGeneration.invalidate()
            youtubeAuthorizationOperation = null
            youtubeOperationProfileEmail = null
            isYouTubeAuthorizationLaunched = false
            isYouTubeAccountActionInProgress = false
            youtubePreferencesStore.clearAccountData()
            updateYouTubeAccount(null)
            verifiedYouTubeProfileEmail = null
            youtubeAccountVerificationState = YouTubeAccountVerificationState.UNVERIFIED
            val defaults = youtubePreferencesStore.loadBroadcastSettings()
            broadcastTitle = defaults.title
            isBroadcastTitleGeneratedDefault = !youtubePreferencesStore.hasSavedBroadcastTitle()
            broadcastDescription = defaults.description
            broadcastPrivacy = defaults.privacy
            broadcastAudience = when (defaults.madeForKids) {
                true -> "true"
                false -> "false"
                null -> "unset"
            }
            broadcastCategoryId = defaults.categoryId
        }
        youtubeAccountGuidance = null
        previousProfileEmail = session?.profileEmail
        if (session != null && isAccountDeletionPending) {
            if (backStack.lastOrNull() !in setOf(SettingsRoute, AccountSettingsRoute)) {
                backStack.clear()
                backStack.add(SettingsRoute)
            }
        } else if (session == null && backStack.lastOrNull() != LoginRoute) {
            selectedBroadcastPlatform = broadcastPlatformOptions.first()
            backStack.clear()
            backStack.add(LoginRoute)
        } else if (session != null && backStack.lastOrNull() == LoginRoute) {
            backStack.clear()
            backStack.add(LiveRoute)
        }
    }

    DisposableEffect(context, selectedCameraLensFacing) {
        var isDisposed = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        supportedCameraResolutions = emptyList()
        selectedResolutionKey = null
        cameraProviderFuture.addListener(
            {
                if (!isDisposed) {
                    supportedCameraResolutions = runCatching {
                        val cameraSelector = when (selectedCameraLensFacing) {
                            CameraLensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                            CameraLensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                        }
                        cameraSelector
                            .filter(cameraProviderFuture.get().availableCameraInfos)
                            .firstOrNull()
                            ?.supportedCameraResolutions()
                            .orEmpty()
                    }.getOrDefault(emptyList())
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose { isDisposed = true }
    }

    LaunchedEffect(selectedCameraLensFacing, selectedResolutionKey) {
        videoQualityCaptureState = VideoQualityCaptureState()
    }
    LaunchedEffect(supportedCameraResolutions, selectedResolutionKey) {
        if (supportedCameraResolutions.none { resolution ->
                resolution.key == selectedResolutionKey
            }
        ) {
            selectedResolutionKey = supportedCameraResolutions.firstOrNull()?.key
        }
    }

    val selectedResolution = supportedCameraResolutions.firstOrNull { resolution ->
        resolution.key == selectedResolutionKey
    }
    val selectedAudioInput = audioInputDevices.firstOrNull { device ->
        device.id == selectedAudioDeviceId
    } ?: audioInputDevices.firstOrNull()
    val selectedAudioDevice = audioDeviceOptions.firstOrNull { device ->
        device.id == selectedAudioDeviceId
    } ?: audioDeviceOptions.firstOrNull()
    val broadcastSettings = BroadcastSettings(
        title = displayedBroadcastTitle,
        description = broadcastDescription,
        privacy = broadcastPrivacy,
        madeForKids = when (broadcastAudience) {
            "true" -> true
            "false" -> false
            else -> null
        },
        categoryId = broadcastCategoryId,
    )
    fun updateBroadcastSettings(settings: BroadcastSettings) {
        isBroadcastTitleGeneratedDefault = isBroadcastTitleGeneratedDefault &&
            settings.title == displayedBroadcastTitle
        broadcastTitle = settings.title
        broadcastDescription = settings.description
        broadcastPrivacy = settings.privacy
        broadcastAudience = when (settings.madeForKids) {
            true -> "true"
            false -> "false"
            null -> "unset"
        }
        broadcastCategoryId = settings.categoryId
        youtubePreferencesStore.saveBroadcastSettings(
            settings,
            titleIsGeneratedDefault = isBroadcastTitleGeneratedDefault,
        )
    }
    LaunchedEffect(selectedAudioInput?.id) {
        webRtcSession.selectAudioInput(selectedAudioInput)
    }
    val onBack: () -> Unit = {
        if (isDeletingAccount || isAccountDeletionPending) {
            Unit
        } else if (backStack.size > 1) {
            backStack.removeLastOrNull()
        } else {
            (context as? Activity)?.finish()
        }
    }
    // 설정 페이지를 벗어나면 그 위에 열린 하위 설정까지 모두 닫고 방송 화면으로 돌아옵니다.
    val closeSettings: () -> Unit = closeSettings@{
        if (isDeletingAccount || isAccountDeletionPending || LiveRoute !in backStack) return@closeSettings
        while (backStack.lastOrNull() != LiveRoute) backStack.removeLastOrNull()
    }

    val connectYouTube: () -> Unit = connectYouTube@{
        if (
            !canChangeYouTubeAccountNow() ||
            isYouTubeAccountActionInProgress ||
            youtubeAccountVerificationState == YouTubeAccountVerificationState.CHECKING
        ) {
            return@connectYouTube
        }

        val operation = youtubeOperationGeneration.begin()
        youtubeOperationProfileEmail = session?.profileEmail
        youtubeAccountStatusBeforeAuthorization = youtubeAccountStatus
        youtubeAuthorizationOperation = null
        isYouTubeAccountActionInProgress = true
        isYouTubeAuthorizationLaunched = false
        youtubeAccountStatus = UiText.Resource(R.string.youtube_status_starting)
        coroutineScope.launch {
            try {
                youtubeCoordinator.beginAuthorization(
                    onAuthorizationRequired = { pendingIntent ->
                        if (isCurrentYouTubeOperation(operation) &&
                            isYouTubeAccountActionInProgress
                        ) {
                            if (!canChangeYouTubeAccountNow()) {
                                cancelYouTubeAccountChange(operation)
                                return@beginAuthorization
                            }
                            youtubeAuthorizationOperation = operation
                            isYouTubeAuthorizationLaunched = true
                            try {
                                authorizationLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent).build(),
                                )
                            } catch (exception: Exception) {
                                youtubeAuthorizationOperation = null
                                isYouTubeAuthorizationLaunched = false
                                showYouTubeAccountFailure(operation, exception, "google_launch")
                            }
                        }
                    },
                    onAuthorized = { serverAuthCode ->
                        if (isCurrentYouTubeOperation(operation) &&
                            isYouTubeAccountActionInProgress
                        ) {
                            if (!canChangeYouTubeAccountNow()) {
                                cancelYouTubeAccountChange(operation)
                                return@beginAuthorization
                            }
                            youtubeAuthorizationOperation = null
                            isYouTubeAuthorizationLaunched = false
                            completeYouTubeConnection(operation, serverAuthCode)
                        }
                    },
                    onFailure = { exception ->
                        if (isCurrentYouTubeOperation(operation)) {
                            isYouTubeAuthorizationLaunched = false
                            showYouTubeAccountFailure(operation, exception, "google_authorize")
                        }
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showYouTubeAccountFailure(operation, exception, "authorization_setup")
            }
        }
    }

    val deleteAccount: () -> Unit = deleteAccount@{
        if (isDeletingAccount) return@deleteAccount
        if (authenticationSession.session.value == null) return@deleteAccount

        youtubeAuthorizationOperation = null
        youtubeOperationGeneration.invalidate()
        isYouTubeAuthorizationLaunched = false
        isYouTubeAccountActionInProgress = false
        youtubeCoordinator.close()
        authenticationSession.deleteAccount(webRtcSession::close)
    }

    val showStudio = LiveRoute in backStack && session != null && !isAccountDeletionPending
    // Keep navigation callbacks current while the studio remains composed below settings.
    val visibleYouTubeAccount = youtubeAccount.takeIf {
        session != null && previousProfileEmail == session?.profileEmail
    }
    val hasServerVerifiedYouTubeAccount = hasVerifiedYouTubeAccount(
        account = visibleYouTubeAccount,
        verificationState = youtubeAccountVerificationState,
        verifiedProfileEmail = verifiedYouTubeProfileEmail,
        currentProfileEmail = session?.profileEmail,
    )
    val isYouTubeAccountOperationInProgress = isYouTubeAccountActionInProgress ||
        youtubeAccountVerificationState == YouTubeAccountVerificationState.CHECKING
    val isYouTubeAccountChangeEnabled = canChangeYouTubeAccountNow()
    val liveScreenProps = rememberUpdatedState(
        LiveScreenProps(
            cameraLensFacing = selectedCameraLensFacing,
            canSwitchCamera = cameraDeviceOptions.size > 1,
            onSwitchCamera = {
                val currentIndex = cameraDeviceOptions.indexOf(selectedCameraLensFacing)
                val nextIndex = if (currentIndex < 0) {
                    0
                } else {
                    (currentIndex + 1) % cameraDeviceOptions.size
                }
                cameraDeviceOptions.getOrNull(nextIndex)?.let { facing ->
                    selectedCameraLensFacing = facing
                }
            },
            cameraResolution = selectedResolution,
            videoQualitySettings = videoQualitySettings,
            onVideoQualitySettingsChanged = updateVideoQualitySettings,
            videoQualityCaptureState = videoQualityCaptureState,
            onVideoQualityCaptureStateChanged = { videoQualityCaptureState = it },
            broadcastSettings = broadcastSettings,
            onBroadcastSettingsChanged = ::updateBroadcastSettings,
            youtubeChannelTitle = visibleYouTubeAccount?.channelTitle,
            hasYouTubeAccount = hasServerVerifiedYouTubeAccount,
            youtubeAccountStatus = youtubeAccountStatus.asString(),
            isYouTubeReconnectRequired = visibleYouTubeAccount?.reconnectRequired == true,
            isYouTubeAccountActionInProgress = isYouTubeAccountOperationInProgress,
            isYouTubeConnectEnabled = isYouTubeAccountChangeEnabled,
            onConnectYouTube = connectYouTube,
            onRefreshAccessToken = ::refreshCurrentAccessToken,
            onAuthenticationExpired = {
                webRtcSession.close()
                authenticationSession.clear()
            },
            onGetAccessToken = { authenticationSession.session.value?.accessToken },
            profileEmail = session?.profileEmail.orEmpty(),
            planUsage = displayedPlanUsage,
            onOpenSettings = {
                if (backStack.lastOrNull() == LiveRoute) backStack.add(SettingsRoute)
            },
        ),
    )

    // 설정 페이지는 넘기는 동안에도 내용이 보이도록 방송 화면 항목에서도 같은 설정 화면을 그립니다.
    val settingsProps: @Composable () -> SettingsScreenProps = {
        SettingsScreenProps(
            onBack = closeSettings,
            onOpenBroadcastSettings = {
                backStack.add(BroadcastSettingRoute)
            },
            onOpenAccountSettings = {
                if (backStack.lastOrNull() != AccountSettingsRoute) backStack.add(AccountSettingsRoute)
            },
            selectedResolution = selectedResolution?.displayName.orEmpty(),
            selectedCameraDevice = selectedCameraLensFacing.settingDisplayText().asString(),
            selectedAudioDevice = selectedAudioDevice?.settingDisplayText()?.asString().orEmpty(),
            selectedPlatform = selectedBroadcastPlatform,
            broadcastAccount = visibleYouTubeAccount?.channelTitle ?: youtubeAccountStatus.asString(),
            onOpenResolutionOptions = {
                backStack.add(SettingOptionRoute(SettingOptionType.CAMERA_RESOLUTION))
            },
            onOpenCameraDeviceOptions = {
                backStack.add(SettingOptionRoute(SettingOptionType.CAMERA_DEVICE))
            },
            onOpenAudioDeviceOptions = {
                backStack.add(SettingOptionRoute(SettingOptionType.AUDIO_DEVICE))
            },
            videoQualitySettings = videoQualitySettings,
            onVideoQualitySettingsChanged = updateVideoQualitySettings,
            videoQualityCaptureState = videoQualityCaptureState,
            profileName = session?.profileName.orEmpty(),
            profileEmail = session?.profileEmail.orEmpty(),
            profileSignInMethod = session?.signInMethod ?: SignInMethod.UNKNOWN,
            planUsage = displayedPlanUsage,
            planError = planError,
            isPlanLoading = isPlanLoading,
            onRefreshPlan = { planRefresh++ },
            onLogout = {
                if (
                    !isDeletingAccount &&
                    !isAccountDeletionPending
                ) {
                    youtubeAuthorizationOperation = null
                    youtubeOperationGeneration.invalidate()
                    youtubeOperationProfileEmail = null
                    isYouTubeAuthorizationLaunched = false
                    webRtcSession.close()
                    authenticationSession.clear()
                    youtubePreferencesStore.clearAccountData()
                    updateYouTubeAccount(null)
                    verifiedYouTubeProfileEmail = null
                    youtubeAccountVerificationState =
                        YouTubeAccountVerificationState.UNVERIFIED
                    youtubeAccountStatus = UiText.Resource(
                        R.string.youtube_status_sign_in_required,
                    )
                    isYouTubeAccountActionInProgress = false
                    backStack.clear()
                    backStack.add(LoginRoute)
                }
            },
            onDeleteAccount = deleteAccount,
            isDeletingAccount = isDeletingAccount,
            isAccountDeletionPending = isAccountDeletionPending,
            isAccountDeletionCleanupPending =
                accountDeletionState.localCleanupPending,
            accountDeletionError = accountDeletionState.error,
            onDeviceProcessing = webRtcSession.selectedOnDeviceProcessing,
            canChangeAIProcessing = webRtcSession.canChangeAIProcessing,
            onSelectAIProcessing = { onDevice ->
                webRtcSession.selectAIProcessing(context, onDevice)
            },
            isAdministrator = isAdministrator,
            faceSwapEnabled = webRtcSession.selectedFaceSwap,
            onFaceSwapChanged = { enabled -> webRtcSession.selectFaceSwap(context, enabled) },
            canReplayBroadcastGuide = webRtcSession.sessionSnapshot == null &&
                webRtcSession.broadcastState == BroadcastState.IDLE &&
                !webRtcSession.isPreparingBroadcast,
            onReplayBroadcastGuide = {
                broadcastTutorial.restart()
                closeSettings()
            },
            onOpenRecordings = { if (canOpenRecordings) backStack.add(RecordingsRoute) },
            canOpenRecordings = canOpenRecordings,
        )
    }
    val settingsScreen: @Composable () -> Unit = { SettingsScreen(settingsProps()) }

    val navigation: @Composable () -> Unit = {
    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = backStack,
        onBack = onBack,
        entryProvider = { route ->
            when (route) {
                LoginRoute -> {
                    NavEntry(route) {
                        LoginScreen(
                            props = LoginScreenProps(
                                onLogin = {
                                    if (authenticationSession.reload() != null) {
                                        backStack.clear()
                                        backStack.add(LiveRoute)
                                    }
                                },
                                onGoogleLogin = { authenticationSession.startGoogleSignIn(context) },
                                onGoogleSignInSuccess =
                                    authenticationSession::acknowledgeGoogleSignInSuccess,
                                googleSignInState = googleSignInState,
                                onEmailLogin = authenticationSession::signInWithEmail,
                                onEmailSignUp = authenticationSession::startEmailSignup,
                                onEmailVerification = authenticationSession::verifyEmailSignup,
                                onEmailSignupResend = authenticationSession::resendEmailSignup,
                                onEmailSignupCancel = authenticationSession::cancelEmailSignup,
                                hasPendingEmailSignup = authenticationSession::hasPendingEmailSignup,
                                isEmailSignupVerified = authenticationSession::isEmailSignupVerified,
                            ),
                        )
                    }
                }

                LiveRoute -> {
                    NavEntry(route) { if (showStudio) settingsScreen() }
                }

                SettingsRoute -> {
                    NavEntry(route) { settingsScreen() }
                }

                AccountSettingsRoute -> {
                    NavEntry(route) { AccountSettingsScreen(settingsProps(), onBack = onBack) }
                }

                RecordingsRoute -> {
                    NavEntry(route) {
                        BroadcastRecordingLibraryScreen(
                            isSaving = webRtcSession.recordingState.status == BroadcastRecordingStatus.SAVING,
                            onBack = onBack,
                        )
                    }
                }

                BroadcastSettingRoute -> {
                    NavEntry(route) {
                        val saveDisabledReasonRes = broadcastSettingsSaveDisabledReason(
                            provider = webRtcSession.selectedProvider,
                            connectionState = webRtcSession.connectionState,
                            broadcastState = webRtcSession.broadcastState,
                            audienceSelected = broadcastAudience != "unset",
                        )
                        val connectDisabledReasonRes = when {
                            session == null -> R.string.youtube_status_sign_in_required
                            isYouTubeAccountOperationInProgress ->
                                R.string.youtube_account_action_in_progress
                            !isYouTubeAccountChangeEnabled ->
                                R.string.youtube_account_change_unavailable
                            else -> null
                        }
                        BroadcastSetting(
                            props = BroadcastSettingProps(
                                onBack = onBack,
                                selectedPlatform = selectedBroadcastPlatform,
                                onOpenPlatformOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.BROADCAST_PLATFORM),
                                    )
                                },
                                title = displayedBroadcastTitle,
                                onTitleChanged = { value ->
                                    updateBroadcastSettings(
                                        broadcastSettings.copy(title = value.take(100)),
                                    )
                                },
                                description = broadcastDescription,
                                onDescriptionChanged = { value ->
                                    updateBroadcastSettings(
                                        broadcastSettings.copy(description = value.take(5_000)),
                                    )
                                },
                                selectedPrivacy = broadcastPrivacyOptions
                                    .first { option -> option.key == broadcastPrivacy }
                                    .label
                                    .asString(),
                                onOpenPrivacyOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.BROADCAST_PRIVACY),
                                    )
                                },
                                selectedAudience = broadcastAudienceOptions
                                    .first { option -> option.key == broadcastAudience }
                                    .label
                                    .asString(),
                                onOpenAudienceOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.BROADCAST_AUDIENCE),
                                    )
                                },
                                categoryId = broadcastCategoryId,
                                onCategoryIdChanged = { value ->
                                    updateBroadcastSettings(
                                        broadcastSettings.copy(
                                            categoryId = value.filter(Char::isDigit),
                                        ),
                                    )
                                },
                                youtubeChannelTitle = visibleYouTubeAccount?.channelTitle,
                                youtubeAccountStatus = youtubeAccountStatus.asString(),
                                hasVerifiedYouTubeAccount = hasServerVerifiedYouTubeAccount,
                                isYouTubeReconnectRequired =
                                    visibleYouTubeAccount?.reconnectRequired == true,
                                isYouTubeAccountActionInProgress =
                                    isYouTubeAccountOperationInProgress,
                                isYouTubeConnectEnabled = isYouTubeAccountChangeEnabled,
                                connectDisabledReasonRes = connectDisabledReasonRes,
                                onConnectYouTube = connectYouTube,
                                onSave = {
                                    webRtcSession.saveBroadcastSettings(broadcastSettings)
                                },
                                isSaveEnabled = saveDisabledReasonRes == null,
                                saveDisabledReasonRes = saveDisabledReasonRes,
                                statusMessage = if (
                                    webRtcSession.connectionState == WebRtcConnectionState.CONNECTED
                                ) {
                                    webRtcSession.broadcastStatus.asString()
                                } else {
                                    stringResource(R.string.broadcast_settings_connection_required)
                                },
                            ),
                        )
                    }
                }

                is SettingOptionRoute -> {
                    val config = when (route.type) {
                        SettingOptionType.CAMERA_RESOLUTION -> OptionSelectionConfig(
                            title = UiText.Resource(R.string.label_camera_resolution),
                            options = supportedCameraResolutions.map { resolution ->
                                SettingOption(
                                    key = resolution.key,
                                    label = UiText.Dynamic(resolution.displayName),
                                )
                            },
                            selectedKey = selectedResolutionKey.orEmpty(),
                            onOptionSelected = { key -> selectedResolutionKey = key },
                        )

                        SettingOptionType.CAMERA_DEVICE -> OptionSelectionConfig(
                            title = UiText.Resource(R.string.label_camera_device),
                            options = cameraDeviceOptions.map { facing ->
                                SettingOption(
                                    key = facing.name,
                                    label = facing.settingDisplayText(),
                                )
                            },
                            selectedKey = selectedCameraLensFacing.name,
                            onOptionSelected = { key ->
                                selectedCameraLensFacing = CameraLensFacing.valueOf(key)
                            },
                        )

                        SettingOptionType.AUDIO_DEVICE -> OptionSelectionConfig(
                            title = UiText.Resource(R.string.label_audio_device),
                            options = audioDeviceOptions.map { device ->
                                SettingOption(
                                    key = device.id.toString(),
                                    label = device.settingDisplayText(),
                                )
                            },
                            selectedKey = selectedAudioDeviceId.toString(),
                            onOptionSelected = { key -> selectedAudioDeviceId = key.toInt() },
                        )

                        SettingOptionType.BROADCAST_PLATFORM -> OptionSelectionConfig(
                            title = UiText.Resource(R.string.label_broadcast_platform),
                            options = broadcastPlatformOptions.map { platform ->
                                SettingOption(key = platform, label = UiText.Dynamic(platform))
                            },
                            selectedKey = selectedBroadcastPlatform,
                            onOptionSelected = { selectedBroadcastPlatform = it },
                        )

                        SettingOptionType.BROADCAST_PRIVACY -> OptionSelectionConfig(
                            title = UiText.Resource(R.string.label_broadcast_privacy),
                            options = broadcastPrivacyOptions,
                            selectedKey = broadcastPrivacy,
                            onOptionSelected = { key ->
                                updateBroadcastSettings(broadcastSettings.copy(privacy = key))
                            },
                        )

                        SettingOptionType.BROADCAST_AUDIENCE -> OptionSelectionConfig(
                            title = UiText.Resource(R.string.label_made_for_kids),
                            options = broadcastAudienceOptions,
                            selectedKey = broadcastAudience,
                            onOptionSelected = { key ->
                                updateBroadcastSettings(
                                    broadcastSettings.copy(
                                        madeForKids = when (key) {
                                            "true" -> true
                                            "false" -> false
                                            else -> null
                                        },
                                    ),
                                )
                            },
                        )
                    }

                    NavEntry(route) {
                        OptionSelectionScreen(
                            title = config.title,
                            options = config.options,
                            selectedKey = config.selectedKey,
                            onOptionSelected = config.onOptionSelected,
                            onBack = onBack,
                        )
                    }
                }
            }
        },
    )
    }
    Box(modifier.fillMaxSize()) {
        // 카메라는 Activity 수명 동안 유지하고, 설정은 그 위 페이지로 넘겨 봅니다. 설정 화면 이동이 캡처를 멈추지 않습니다.
        if (showStudio) {
            LiveScreen(
                webRtcSession = webRtcSession,
                props = liveScreenProps.value,
                tutorial = broadcastTutorial,
                settings = LiveSettingsPage(
                    isOpen = backStack.lastOrNull() != LiveRoute,
                    canLeaveBySwipe = backStack.lastOrNull() in setOf(LiveRoute, SettingsRoute) &&
                        !isDeletingAccount && !isAccountDeletionPending,
                    onOpen = { if (backStack.lastOrNull() == LiveRoute) backStack.add(SettingsRoute) },
                    onClose = closeSettings,
                    content = navigation,
                ),
            )
        } else {
            navigation()
        }
    }
    youtubeAccountGuidance?.let { guidance ->
        ServerErrorDialog(
            guidance = guidance,
            onDismiss = { youtubeAccountGuidance = null },
            onAction = if (guidance.action in setOf(
                    ServerErrorAction.CONNECT, ServerErrorAction.RECONNECT, ServerErrorAction.RETRY, ServerErrorAction.LOGIN,
                )) {
                {
                    youtubeAccountGuidance = null
                    if (guidance.action == ServerErrorAction.LOGIN) {
                        webRtcSession.close()
                        authenticationSession.clear()
                    } else connectYouTube()
                }
            } else null,
        )
    }
}

/** YouTube 계정 상태를 보여 주거나 쓰는 화면입니다. 이 화면 사이를 오가도 계정 조회를 다시 시작합니다. */
private val YouTubeAccountCheckRoutes = setOf(BroadcastSettingRoute, LiveRoute, SettingsRoute)
