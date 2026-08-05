package com.example.innolive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.example.innolive.feature.live.LiveScreen
import com.example.innolive.feature.live.LiveScreenProps
import com.example.innolive.feature.login.LoginScreen
import com.example.innolive.feature.login.LoginScreenProps
import com.example.innolive.feature.login.oauth.google.continueWithGoogle
import com.example.innolive.feature.settings.SettingsScreen
import com.example.innolive.feature.settings.SettingsScreenProps
import com.example.innolive.feature.settings.broadcast.BroadcastSetting
import com.example.innolive.feature.settings.broadcast.BroadcastSettingProps
import com.example.innolive.feature.settings.camera.CameraSetting
import com.example.innolive.feature.settings.camera.CameraSettingProps
import com.example.innolive.feature.settings.selection.OptionSelectionScreen
import com.example.innolive.ui.theme.MyApplicationTheme

sealed interface AppRoute
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
    val options: List<String>,
    val onOptionSelected: (String) -> Unit,
)

private val cameraResolutionOptions = listOf(
    "1080p - 30fps",
    "1080p - 24fps",
    "720p - 30fps",
    "720p - 24fps",
)

private val cameraDeviceOptions = listOf(
    "후면 카메라",
    "전면 카메라",
)

private val audioDeviceOptions = listOf(
    "기본 마이크",
    "DJI Mic Mini 2",
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
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(
                        modifier = Modifier
                            .padding(innerPadding)
                            .background(color = MaterialTheme.colorScheme.background)
                    )
                }
            }
        }
    }
}
@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backStack = remember {
        mutableStateListOf<AppRoute>(LoginRoute)
    }
    var selectedResolution by rememberSaveable {
        mutableStateOf(cameraResolutionOptions.first())
    }
    var selectedCameraDevice by rememberSaveable {
        mutableStateOf(cameraDeviceOptions.first())
    }
    var selectedAudioDevice by rememberSaveable {
        mutableStateOf(audioDeviceOptions.first())
    }
    var selectedBroadcastPlatform by rememberSaveable {
        mutableStateOf(broadcastPlatformOptions.first())
    }

    val onBack: () -> Unit = {
        backStack.removeLastOrNull()
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
                                    backStack.removeLastOrNull()
                                    backStack.add(LiveRoute)
                                },
                                onGoogleLogin = { continueWithGoogle(context) },
                            ),
                        )
                    }
                }

                is LiveRoute -> {
                    NavEntry(route) {
                        LiveScreen(
                            props = LiveScreenProps(
                                onOpenSettings = {
                                    backStack.add(SettingsRoute)
                                },
                            ),
                        )
                    }
                }

                is SettingsRoute -> {
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
                            ),
                        )
                    }
                }

                is CameraSettingRoute -> {
                    NavEntry(route) {
                        CameraSetting(
                            props = CameraSettingProps(
                                onBack = onBack,
                                selectedResolution = selectedResolution,
                                selectedCameraDevice = selectedCameraDevice,
                                selectedAudioDevice = selectedAudioDevice,
                                onOpenResolutionOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.CAMERA_RESOLUTION)
                                    )
                                },
                                onOpenCameraDeviceOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.CAMERA_DEVICE)
                                    )
                                },
                                onOpenAudioDeviceOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.AUDIO_DEVICE)
                                    )
                                },
                            )
                        )
                    }
                }

                is BroadcastSettingRoute -> {
                    NavEntry(route) {
                        BroadcastSetting(
                            props = BroadcastSettingProps(
                                onBack = onBack,
                                selectedPlatform = selectedBroadcastPlatform,
                                onOpenPlatformOptions = {
                                    backStack.add(
                                        SettingOptionRoute(SettingOptionType.BROADCAST_PLATFORM)
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
                            options = cameraResolutionOptions,
                            onOptionSelected = { selectedResolution = it },
                        )

                        SettingOptionType.CAMERA_DEVICE -> OptionSelectionConfig(
                            title = "카메라 기기",
                            options = cameraDeviceOptions,
                            onOptionSelected = { selectedCameraDevice = it },
                        )

                        SettingOptionType.AUDIO_DEVICE -> OptionSelectionConfig(
                            title = "오디오 기기",
                            options = audioDeviceOptions,
                            onOptionSelected = { selectedAudioDevice = it },
                        )

                        SettingOptionType.BROADCAST_PLATFORM -> OptionSelectionConfig(
                            title = "방송 플랫폼",
                            options = broadcastPlatformOptions,
                            onOptionSelected = { selectedBroadcastPlatform = it },
                        )
                    }

                    NavEntry(route) {
                        OptionSelectionScreen(
                            title = config.title,
                            options = config.options,
                            onOptionSelected = config.onOptionSelected,
                            onBack = onBack,
                        )
                    }
                }
            }
        }
    )
}
