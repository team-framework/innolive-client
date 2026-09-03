package com.framework.innolive.app

import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
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
    val title: String,
    val options: List<SettingOption>,
    val selectedKey: String,
    val onOptionSelected: (String) -> Unit,
)

private val broadcastPlatformOptions = listOf(
    "YouTube",
)

private val broadcastPrivacyOptions = listOf(
    SettingOption(key = "public", label = "공개"),
    SettingOption(key = "unlisted", label = "일부 공개"),
    SettingOption(key = "private", label = "비공개"),
)

private val broadcastAudienceOptions = listOf(
    SettingOption(key = "unset", label = "선택 필요"),
    SettingOption(key = "true", label = "아동용"),
    SettingOption(key = "false", label = "아동용 아님"),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val webRtcSession = ViewModelProvider(this)[WebRtcSessionViewModel::class.java]
        val authenticationSession =
            ViewModelProvider(this)[AuthenticationSessionViewModel::class.java]
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(
                        webRtcSession = webRtcSession,
                        authenticationSession = authenticationSession,
                        modifier = Modifier
                            .padding(innerPadding)
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
    val youtubeCoordinator = remember(activity) { YouTubeAccountCoordinator(activity) }
    var youtubeAccountProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var youtubeAccountChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var youtubeAccountChannelTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var youtubeAccountReconnectRequired by rememberSaveable { mutableStateOf(false) }
    val youtubeAccount = youtubeAccountProvider?.let { provider ->
        StreamingAccount(
            provider = provider,
            channelId = youtubeAccountChannelId.orEmpty(),
            channelTitle = youtubeAccountChannelTitle.orEmpty(),
            reconnectRequired = youtubeAccountReconnectRequired,
        )
    }
    var youtubeAccountStatus by rememberSaveable {
        mutableStateOf("로그인 후 YouTube 계정을 연동할 수 있습니다.")
    }
    var isYouTubeAccountActionInProgress by rememberSaveable { mutableStateOf(false) }
    var isYouTubeAuthorizationLaunched by rememberSaveable { mutableStateOf(false) }
    var youtubeAuthorizationOperation by rememberSaveable { mutableStateOf<Long?>(null) }
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
            youtubeOperationGeneration.invalidate()
            isYouTubeAccountActionInProgress = false
            youtubeAccountStatus = "YouTube 계정 연동을 다시 시도해 주세요."
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
            account == null -> "연결된 YouTube 계정이 없습니다."
            account.reconnectRequired -> "YouTube 재연동이 필요합니다."
            account.channelTitle.isNotBlank() -> "YouTube 채널: ${account.channelTitle}"
            else -> "YouTube 계정이 연동되었습니다."
        }
    }

    fun showYouTubeAccountFailure(operation: Long) {
        if (!youtubeOperationGeneration.isCurrent(operation) || session == null) return
        youtubeAuthorizationOperation = null
        isYouTubeAuthorizationLaunched = false
        isYouTubeAccountActionInProgress = false
        youtubeAccountStatus = "YouTube 계정 연동에 실패했습니다. 다시 시도해 주세요."
    }

    fun isCurrentYouTubeOperation(operation: Long): Boolean =
        youtubeOperationGeneration.isCurrent(operation) && session != null

    fun completeYouTubeConnection(operation: Long, serverAuthCode: String) {
        if (!isCurrentYouTubeOperation(operation) || !isYouTubeAccountActionInProgress) return
        youtubeAuthorizationOperation = null
        isYouTubeAuthorizationLaunched = false
        coroutineScope.launch {
            try {
                val account = youtubeCoordinator.connect(serverAuthCode, ::refreshCurrentAccessToken)
                if (isCurrentYouTubeOperation(operation)) updateYouTubeAccount(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                showYouTubeAccountFailure(operation)
            } finally {
                if (youtubeOperationGeneration.isCurrent(operation)) {
                    youtubeAuthorizationOperation = null
                    isYouTubeAuthorizationLaunched = false
                    isYouTubeAccountActionInProgress = false
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
                    .onFailure { showYouTubeAccountFailure(operation) }
            } else {
                isYouTubeAuthorizationLaunched = false
                isYouTubeAccountActionInProgress = false
                youtubeAccountStatus = "YouTube 권한 동의를 취소했습니다."
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
        mutableStateListOf<AppRoute>(if (session == null) LoginRoute else LiveRoute)
    }
    LaunchedEffect(session) {
        if (session != null && backStack.lastOrNull() == LoginRoute) {
            backStack.clear()
            backStack.add(LiveRoute)
        }
    }
    LaunchedEffect(
        backStack.lastOrNull(),
        session?.profileEmail,
        isYouTubeAccountActionInProgress,
    ) {
        if (isYouTubeAccountActionInProgress) return@LaunchedEffect

        val operation = youtubeOperationGeneration.begin()
        if (backStack.lastOrNull() in setOf(BroadcastSettingRoute, LiveRoute) && session != null) {
            youtubeAccountStatus = "YouTube 연결 상태를 확인하는 중입니다."
            try {
                val account = youtubeCoordinator.loadAccount(::refreshCurrentAccessToken)
                if (isCurrentYouTubeOperation(operation)) updateYouTubeAccount(account)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (isCurrentYouTubeOperation(operation)) {
                    youtubeAccountStatus = "YouTube 연결 상태를 확인하지 못했습니다."
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
    var broadcastTitle by rememberSaveable { mutableStateOf("") }
    var broadcastDescription by rememberSaveable { mutableStateOf("") }
    var broadcastPrivacy by rememberSaveable { mutableStateOf("private") }
    var broadcastAudience by rememberSaveable { mutableStateOf("unset") }
    var broadcastCategoryId by rememberSaveable { mutableStateOf("") }

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
        title = broadcastTitle,
        description = broadcastDescription,
        privacy = broadcastPrivacy,
        madeForKids = when (broadcastAudience) {
            "true" -> true
            "false" -> false
            else -> null
        },
        categoryId = broadcastCategoryId,
    )
    LaunchedEffect(selectedAudioInput?.id) {
        webRtcSession.selectAudioInput(selectedAudioInput)
    }
    val onBack: () -> Unit = {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        } else {
            (context as? Activity)?.finish()
        }
    }

    val connectYouTube: () -> Unit = connectYouTube@{
        if (isYouTubeAccountActionInProgress || session == null) {
            return@connectYouTube
        }

        val operation = youtubeOperationGeneration.begin()
        val accountEmail = session?.profileEmail
        youtubeAuthorizationOperation = null
        isYouTubeAccountActionInProgress = true
        isYouTubeAuthorizationLaunched = false
        youtubeAccountStatus = "YouTube 계정 연동을 시작하는 중입니다."
        coroutineScope.launch {
            try {
                youtubeCoordinator.beginAuthorization(
                    accountEmail = accountEmail,
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
                    onFailure = {
                        if (isCurrentYouTubeOperation(operation)) {
                            isYouTubeAuthorizationLaunched = false
                            showYouTubeAccountFailure(operation)
                        }
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                showYouTubeAccountFailure(operation)
            }
        }
    }

    // NavDisplay keeps the LiveRoute NavEntry while the back stack is unchanged.
    // Keep its props up to date independently of NavEntry recreation.
    val liveScreenProps = rememberUpdatedState(
        LiveScreenProps(
            cameraLensFacing = selectedCameraLensFacing,
            cameraResolution = selectedResolution,
            broadcastSettings = broadcastSettings,
            onBroadcastSettingsChanged = { settings ->
                broadcastTitle = settings.title
                broadcastDescription = settings.description
                broadcastPrivacy = settings.privacy
                broadcastAudience = when (settings.madeForKids) {
                    true -> "true"
                    false -> "false"
                    null -> "unset"
                }
                broadcastCategoryId = settings.categoryId
            },
            youtubeChannelTitle = youtubeAccount?.channelTitle,
            youtubeAccountStatus = youtubeAccountStatus,
            isYouTubeReconnectRequired = youtubeAccount?.reconnectRequired == true,
            isYouTubeAccountActionInProgress = isYouTubeAccountActionInProgress,
            isYouTubeConnectEnabled = session != null,
            onConnectYouTube = connectYouTube,
            onRefreshAccessToken = ::refreshCurrentAccessToken,
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
                                onGoogleLogin = {
                                    authenticationSession.continueWithGoogle(context)
                                },
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
                                    youtubeAuthorizationOperation = null
                                    youtubeOperationGeneration.invalidate()
                                    isYouTubeAuthorizationLaunched = false
                                    webRtcSession.close()
                                    authenticationSession.clear()
                                    updateYouTubeAccount(null)
                                    youtubeAccountStatus = "로그인 후 YouTube 계정을 연동할 수 있습니다."
                                    isYouTubeAccountActionInProgress = false
                                    backStack.clear()
                                    backStack.add(LoginRoute)
                                },
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
                                selectedCameraDevice = selectedCameraLensFacing.displayName,
                                selectedAudioDevice = selectedAudioDevice?.displayName.orEmpty(),
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
                        BroadcastSetting(
                            props = BroadcastSettingProps(
                                onBack = onBack,
                                selectedPlatform = selectedBroadcastPlatform,
                                onOpenPlatformOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.BROADCAST_PLATFORM),
                                    )
                                },
                                title = broadcastTitle,
                                onTitleChanged = { value -> broadcastTitle = value.take(100) },
                                description = broadcastDescription,
                                onDescriptionChanged = { value ->
                                    broadcastDescription = value.take(5_000)
                                },
                                selectedPrivacy = broadcastPrivacyOptions
                                    .first { option -> option.key == broadcastPrivacy }
                                    .label,
                                onOpenPrivacyOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.BROADCAST_PRIVACY),
                                    )
                                },
                                selectedAudience = broadcastAudienceOptions
                                    .first { option -> option.key == broadcastAudience }
                                    .label,
                                onOpenAudienceOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.BROADCAST_AUDIENCE),
                                    )
                                },
                                categoryId = broadcastCategoryId,
                                onCategoryIdChanged = { value ->
                                    broadcastCategoryId = value.filter(Char::isDigit)
                                },
                                youtubeChannelTitle = youtubeAccount?.channelTitle,
                                youtubeAccountStatus = youtubeAccountStatus,
                                isYouTubeReconnectRequired = youtubeAccount?.reconnectRequired == true,
                                isYouTubeAccountActionInProgress = isYouTubeAccountActionInProgress,
                                isYouTubeConnectEnabled = session != null,
                                onConnectYouTube = connectYouTube,
                                onSave = {
                                    webRtcSession.saveBroadcastSettings(broadcastSettings)
                                },
                                isSaveEnabled =
                                    webRtcSession.connectionState == WebRtcConnectionState.CONNECTED &&
                                        broadcastAudience != "unset" &&
                                        webRtcSession.broadcastState !in setOf(
                                            BroadcastState.SAVING_SETTINGS,
                                            BroadcastState.PREPARING,
                                            BroadcastState.PREPARED,
                                            BroadcastState.GOING_LIVE,
                                            BroadcastState.LIVE,
                                            BroadcastState.STOPPING,
                                        ),
                                statusMessage = if (
                                    webRtcSession.connectionState == WebRtcConnectionState.CONNECTED
                                ) {
                                    webRtcSession.broadcastStatus
                                } else {
                                    "비식별화 연결 후 방송 설정을 저장할 수 있습니다."
                                },
                            ),
                        )
                    }
                }

                is SettingOptionRoute -> {
                    val config = when (route.type) {
                        SettingOptionType.CAMERA_RESOLUTION -> OptionSelectionConfig(
                            title = "카메라 해상도",
                            options = supportedCameraResolutions.map { resolution ->
                                SettingOption(
                                    key = resolution.key,
                                    label = resolution.displayName,
                                )
                            },
                            selectedKey = selectedResolutionKey.orEmpty(),
                            onOptionSelected = { key -> selectedResolutionKey = key },
                        )

                        SettingOptionType.CAMERA_DEVICE -> OptionSelectionConfig(
                            title = "카메라 기기",
                            options = cameraDeviceOptions.map { facing ->
                                SettingOption(
                                    key = facing.name,
                                    label = facing.displayName,
                                )
                            },
                            selectedKey = selectedCameraLensFacing.name,
                            onOptionSelected = { key ->
                                selectedCameraLensFacing = CameraLensFacing.valueOf(key)
                            },
                        )

                        SettingOptionType.AUDIO_DEVICE -> OptionSelectionConfig(
                            title = "오디오 기기",
                            options = audioDeviceOptions.map { device ->
                                SettingOption(
                                    key = device.id.toString(),
                                    label = device.displayName,
                                )
                            },
                            selectedKey = selectedAudioDeviceId.toString(),
                            onOptionSelected = { key -> selectedAudioDeviceId = key.toInt() },
                        )

                        SettingOptionType.BROADCAST_PLATFORM -> OptionSelectionConfig(
                            title = "방송 플랫폼",
                            options = broadcastPlatformOptions.map { platform ->
                                SettingOption(key = platform, label = platform)
                            },
                            selectedKey = selectedBroadcastPlatform,
                            onOptionSelected = { selectedBroadcastPlatform = it },
                        )

                        SettingOptionType.BROADCAST_PRIVACY -> OptionSelectionConfig(
                            title = "공개 범위",
                            options = broadcastPrivacyOptions,
                            selectedKey = broadcastPrivacy,
                            onOptionSelected = { broadcastPrivacy = it },
                        )

                        SettingOptionType.BROADCAST_AUDIENCE -> OptionSelectionConfig(
                            title = "아동용 콘텐츠",
                            options = broadcastAudienceOptions,
                            selectedKey = broadcastAudience,
                            onOptionSelected = { broadcastAudience = it },
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
