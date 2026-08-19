package com.framework.innolive.app

import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.framework.innolive.feature.live.AudioInputDevice
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.CameraResolution
import com.framework.innolive.feature.live.LiveScreen
import com.framework.innolive.feature.live.LiveScreenProps
import com.framework.innolive.feature.live.WebRtcSessionViewModel
import com.framework.innolive.feature.live.isSelectableAudioInputType
import com.framework.innolive.feature.live.supportedCameraResolutions
import com.framework.innolive.feature.login.LoginScreen
import com.framework.innolive.feature.login.LoginScreenProps
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import com.framework.innolive.feature.login.oauth.google.continueWithGoogle
import com.framework.innolive.feature.login.oauth.google.refreshGoogleSession
import com.framework.innolive.feature.settings.SettingsScreen
import com.framework.innolive.feature.settings.SettingsScreenProps
import com.framework.innolive.feature.settings.broadcast.BroadcastSetting
import com.framework.innolive.feature.settings.broadcast.BroadcastSettingProps
import com.framework.innolive.feature.settings.camera.CameraSetting
import com.framework.innolive.feature.settings.camera.CameraSettingProps
import com.framework.innolive.feature.settings.selection.OptionSelectionScreen
import com.framework.innolive.feature.settings.selection.SettingOption
import com.framework.innolive.ui.theme.MyApplicationTheme
import java.io.Serializable

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
    "Youtube",
    "Chzzk",
    "SOOP",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val webRtcSession = ViewModelProvider(this)[WebRtcSessionViewModel::class.java]
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(
                        webRtcSession = webRtcSession,
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionStore = remember(context) { GoogleSessionStore(context) }
    var session by remember { mutableStateOf(sessionStore.load()) }
    val packageManager = context.packageManager
    val cameraDeviceOptions = remember(packageManager) {
        CameraLensFacing.supported(
            hasBackCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA),
            hasFrontCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT),
        )
    }
    val audioInputDevices = remember(context) {
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { device -> isSelectableAudioInputType(device.type) }
            .distinctBy { device ->
                if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) null else device.id
            }
    }
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
    var selectedBroadcastPlatform by rememberSaveable {
        mutableStateOf(broadcastPlatformOptions.first())
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
                                    session = sessionStore.load()
                                    if (session != null) {
                                        backStack.clear()
                                        backStack.add(LiveRoute)
                                    }
                                },
                                onGoogleLogin = { continueWithGoogle(context) },
                            ),
                        )
                    }
                }

                LiveRoute -> {
                    NavEntry(route) {
                        LiveScreen(
                            webRtcSession = webRtcSession,
                            props = LiveScreenProps(
                                cameraLensFacing = selectedCameraLensFacing,
                                cameraResolution = selectedResolution,
                                onRefreshAccessToken = {
                                    val currentSession = checkNotNull(session) {
                                        "Authentication session is missing."
                                    }
                                    refreshGoogleSession(context, currentSession)
                                        .also { refreshedSession -> session = refreshedSession }
                                        .accessToken
                                },
                                onOpenSettings = {
                                    backStack.add(SettingsRoute)
                                },
                            ),
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
                                    webRtcSession.close()
                                    sessionStore.clear()
                                    session = null
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
