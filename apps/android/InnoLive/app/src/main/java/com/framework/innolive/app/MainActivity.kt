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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.framework.innolive.R
import com.framework.innolive.feature.live.AudioInputDevice
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.CameraResolution
import com.framework.innolive.feature.live.LiveScreen
import com.framework.innolive.feature.live.LiveScreenProps
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.WebRtcSessionViewModel
import com.framework.innolive.feature.live.rememberAudioInputDevices
import com.framework.innolive.feature.live.supportedCameraResolutions
import com.framework.innolive.feature.login.LoginScreen
import com.framework.innolive.feature.login.LoginScreenProps
import com.framework.innolive.feature.login.oauth.google.AuthenticationSessionViewModel
import com.framework.innolive.feature.settings.SettingsScreen
import com.framework.innolive.feature.settings.SettingsScreenProps
import com.framework.innolive.feature.settings.broadcast.BroadcastSetting
import com.framework.innolive.feature.settings.broadcast.BroadcastSettingProps
import com.framework.innolive.feature.settings.camera.CameraSetting
import com.framework.innolive.feature.settings.camera.CameraSettingProps
import com.framework.innolive.feature.settings.selection.OptionSelectionScreen
import com.framework.innolive.feature.settings.selection.SettingOption
import com.framework.innolive.feature.youtube.OperationGeneration
import com.framework.innolive.feature.youtube.StreamingAccount
import com.framework.innolive.feature.youtube.YouTubeAccountCoordinator
import com.framework.innolive.feature.youtube.YouTubeAccountVerificationState
import com.framework.innolive.feature.youtube.YouTubePreferencesStore
import com.framework.innolive.feature.youtube.acceptServerVerifiedYouTubeAccount
import com.framework.innolive.feature.youtube.cancelYouTubeAuthorization
import com.framework.innolive.feature.youtube.defaultYouTubeBroadcastTitle
import com.framework.innolive.feature.youtube.hasVerifiedYouTubeAccount
import com.framework.innolive.feature.youtube.rememberYouTubeVerificationMemory
import com.framework.innolive.feature.youtube.youtubeConnectionFailureMessage
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.UiTextSaver
import com.framework.innolive.ui.text.NullableUiTextSaver
import com.framework.innolive.ui.text.asString
import com.framework.innolive.ui.theme.MyApplicationTheme
import java.io.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface AppRoute : Serializable
data object LoginRoute : AppRoute

data object LiveRoute : AppRoute

data object SettingsRoute : AppRoute

data object CameraSettingRoute : AppRoute

data object BroadcastSettingRoute : AppRoute

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

private fun AudioInputDevice.settingDisplayText(): UiText = if (isDefault) {
    UiText.Resource(R.string.audio_device_default, listOf(name))
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
            removeConnection = youtubePreferencesStore::removeConnection,
        )
        if (!cacheUpdated) {
            Log.w("InnoLiveYouTube", "Unable to update YouTube account display cache.")
        }
    }

    fun isCurrentYouTubeOperation(operation: Long): Boolean =
        youtubeOperationGeneration.isCurrent(operation) &&
            session != null && youtubeOperationProfileEmail == session?.profileEmail

    fun showYouTubeAccountFailure(operation: Long, exception: Throwable? = null) {
        if (!isCurrentYouTubeOperation(operation)) return
        youtubeAuthorizationOperation = null
        isYouTubeAuthorizationLaunched = false
        suppressYouTubeAccountRefreshOnce = true
        isYouTubeAccountActionInProgress = false
        youtubeAccountStatusBeforeAuthorization = null
        youtubeAccountStatus = youtubeConnectionFailureMessage(exception)
    }

    fun completeYouTubeConnection(operation: Long, serverAuthCode: String) {
        if (!isCurrentYouTubeOperation(operation) || !isYouTubeAccountActionInProgress) return
        youtubeAuthorizationOperation = null
        isYouTubeAuthorizationLaunched = false
        coroutineScope.launch {
            try {
                val account = youtubeCoordinator.connect(serverAuthCode, ::refreshCurrentAccessToken)
                if (isCurrentYouTubeOperation(operation)) updateVerifiedYouTubeAccount(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showYouTubeAccountFailure(operation, exception)
            } finally {
                if (isCurrentYouTubeOperation(operation)) {
                    youtubeAuthorizationOperation = null
                    isYouTubeAuthorizationLaunched = false
                    suppressYouTubeAccountRefreshOnce = true
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
                    .onFailure { exception -> showYouTubeAccountFailure(operation, exception) }
            } else {
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
        if (backStack.lastOrNull() in setOf(BroadcastSettingRoute, LiveRoute) && session != null) {
            youtubeAccountVerificationState = YouTubeAccountVerificationState.CHECKING
            youtubeAccountStatus = UiText.Resource(R.string.youtube_status_checking)
            try {
                val account = youtubeCoordinator.loadAccount(::refreshCurrentAccessToken)
                if (isCurrentYouTubeOperation(operation)) updateVerifiedYouTubeAccount(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (isCurrentYouTubeOperation(operation)) {
                    youtubeAccountVerificationState = YouTubeAccountVerificationState.UNVERIFIED
                    youtubeAccountStatus = UiText.Resource(R.string.youtube_status_check_failed)
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
        previousProfileEmail = session?.profileEmail
        if (session != null && isAccountDeletionPending) {
            if (backStack.lastOrNull() != SettingsRoute) {
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

    val connectYouTube: () -> Unit = connectYouTube@{
        if (
            isDeletingAccount ||
            isYouTubeAccountActionInProgress ||
            youtubeAccountVerificationState == YouTubeAccountVerificationState.CHECKING ||
            session == null
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
                            youtubeAuthorizationOperation = operation
                            isYouTubeAuthorizationLaunched = true
                            try {
                                authorizationLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent).build(),
                                )
                            } catch (_: Exception) {
                                youtubeAuthorizationOperation = null
                                isYouTubeAuthorizationLaunched = false
                                showYouTubeAccountFailure(operation)
                            }
                        }
                    },
                    onAuthorized = { serverAuthCode ->
                        if (isCurrentYouTubeOperation(operation) &&
                            isYouTubeAccountActionInProgress
                        ) {
                            youtubeAuthorizationOperation = null
                            isYouTubeAuthorizationLaunched = false
                            completeYouTubeConnection(operation, serverAuthCode)
                        }
                    },
                    onFailure = { exception ->
                        if (isCurrentYouTubeOperation(operation)) {
                            isYouTubeAuthorizationLaunched = false
                            showYouTubeAccountFailure(operation, exception)
                        }
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showYouTubeAccountFailure(operation, exception)
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

    // NavDisplay keeps the LiveRoute NavEntry while the back stack is unchanged.
    // Keep its props up to date independently of NavEntry recreation.
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
            broadcastSettings = broadcastSettings,
            onBroadcastSettingsChanged = ::updateBroadcastSettings,
            youtubeChannelTitle = visibleYouTubeAccount?.channelTitle,
            hasYouTubeAccount = hasServerVerifiedYouTubeAccount,
            youtubeAccountStatus = youtubeAccountStatus.asString(),
            isYouTubeReconnectRequired = visibleYouTubeAccount?.reconnectRequired == true,
            isYouTubeAccountActionInProgress = isYouTubeAccountOperationInProgress,
            isYouTubeConnectEnabled = session != null,
            onConnectYouTube = connectYouTube,
            onRefreshAccessToken = ::refreshCurrentAccessToken,
            onGetAccessToken = { authenticationSession.session.value?.accessToken },
            profileEmail = session?.profileEmail.orEmpty(),
            onOpenSettings = {
                backStack.add(SettingsRoute)
            },
        ),
    )

    NavDisplay(
        modifier = modifier.fillMaxSize(),
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
                    NavEntry(route) {
                        LiveScreen(
                            webRtcSession = webRtcSession,
                            props = liveScreenProps.value,
                        )
                    }
                }

                SettingsRoute -> {
                    NavEntry(route) {
                        SettingsScreen(
                            props = SettingsScreenProps(
                                onBack = onBack,
                                onOpenCameraSettings = {
                                    backStack.add(CameraSettingRoute)
                                },
                                onOpenBroadcastSettings = {
                                    backStack.add(BroadcastSettingRoute)
                                },
                                profileName = session?.profileName.orEmpty(),
                                profileEmail = session?.profileEmail.orEmpty(),
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
                            ),
                        )
                    }
                }

                CameraSettingRoute -> {
                    NavEntry(route) {
                        CameraSetting(
                            props = CameraSettingProps(
                                onBack = onBack,
                                selectedResolution = selectedResolution?.displayName.orEmpty(),
                                selectedCameraDevice =
                                    selectedCameraLensFacing.settingDisplayText().asString(),
                                selectedAudioDevice = selectedAudioDevice
                                    ?.settingDisplayText()
                                    ?.asString()
                                    .orEmpty(),
                                onOpenResolutionOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.CAMERA_RESOLUTION),
                                    )
                                },
                                onOpenCameraDeviceOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.CAMERA_DEVICE),
                                    )
                                },
                                onOpenAudioDeviceOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.AUDIO_DEVICE),
                                    )
                                },
                            ),
                        )
                    }
                }

                BroadcastSettingRoute -> {
                    NavEntry(route) {
                        val saveDisabledReasonRes = when {
                            webRtcSession.connectionState != WebRtcConnectionState.CONNECTED ->
                                R.string.broadcast_settings_connection_required
                            broadcastAudience == "unset" -> R.string.validation_audience
                            webRtcSession.broadcastState == BroadcastState.SAVING_SETTINGS ->
                                R.string.broadcast_settings_saving
                            webRtcSession.broadcastState in setOf(
                                BroadcastState.PREPARING,
                                BroadcastState.PREPARED,
                                BroadcastState.GOING_LIVE,
                                BroadcastState.LIVE,
                                BroadcastState.STOPPING,
                            ) -> R.string.broadcast_settings_save_unavailable
                            else -> null
                        }
                        val connectDisabledReasonRes = when {
                            session == null -> R.string.youtube_status_sign_in_required
                            isYouTubeAccountOperationInProgress ->
                                R.string.youtube_account_action_in_progress
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
                                isYouTubeConnectEnabled = session != null,
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
                                    context.getString(R.string.broadcast_settings_connection_required)
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
