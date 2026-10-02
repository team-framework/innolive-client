package com.framework.innolive.app

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.CameraPreview
import com.framework.innolive.feature.live.WebRtcNativeRuntime
import com.framework.innolive.feature.settings.SettingsScreen
import com.framework.innolive.feature.settings.SettingsScreenProps
import com.framework.innolive.feature.settings.camera.CameraSetting
import com.framework.innolive.feature.settings.camera.CameraSettingProps
import com.framework.innolive.feature.settings.selection.OptionSelectionScreen
import com.framework.innolive.feature.settings.selection.SettingOption
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class StudioSettingsLayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsAndNestedSelectionKeepCameraFramesAndStudioComposition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        WebRtcNativeRuntime.initialize(context)
        val frames = AtomicInteger()
        val starts = AtomicInteger()
        val disposals = AtomicInteger()
        val analyzer = CameraFrameAnalyzer(object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) { frames.incrementAndGet() }
        })
        analyzer.start()
        val routes = mutableStateListOf<AppRoute>(LiveRoute)
        val showStudio = mutableStateOf(true)
        val back: () -> Unit = { routes.removeLastOrNull() }
        compose.setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                StudioSettingsLayer(
                    showStudio = showStudio.value,
                    showSettings = routes.last() != LiveRoute,
                    onDismissSettings = back,
                    studio = {
                        DisposableEffect(Unit) {
                            starts.incrementAndGet()
                            onDispose { disposals.incrementAndGet() }
                        }
                        CameraPreview(CameraLensFacing.BACK, null, frameAnalyzer = analyzer,
                            showAdjustedColorPreview = false)
                    },
                    navigation = {
                        NavDisplay(backStack = routes, onBack = back, entryProvider = { route ->
                            NavEntry(route) {
                                when (route) {
                                    SettingsRoute -> SettingsScreen(SettingsScreenProps(
                                        onBack = back,
                                        onOpenCameraSettings = { routes.add(CameraSettingRoute) },
                                        onOpenBroadcastSettings = { routes.add(BroadcastSettingRoute) },
                                        profileName = "InnoLive User", profileEmail = "user@example.com", onLogout = {},
                                        selectedResolution = "1920×1080", selectedCameraDevice = "Camera",
                                        selectedAudioDevice = "Microphone", selectedPlatform = "YouTube",
                                        broadcastAccount = "InnoLive",
                                        onOpenResolutionOptions = {
                                            routes.add(SettingOptionRoute(SettingOptionType.CAMERA_RESOLUTION))
                                        },
                                    ))
                                    CameraSettingRoute -> CameraSetting(CameraSettingProps(
                                        onBack = back, selectedResolution = "1920×1080",
                                        selectedCameraDevice = "Camera", selectedAudioDevice = "Microphone",
                                        onOpenResolutionOptions = {
                                            routes.add(SettingOptionRoute(SettingOptionType.CAMERA_RESOLUTION))
                                        }, onOpenCameraDeviceOptions = {}, onOpenAudioDeviceOptions = {},
                                    ))
                                    is SettingOptionRoute -> OptionSelectionScreen(
                                        title = UiText.Resource(R.string.label_camera_resolution),
                                        options = listOf(SettingOption("1080", UiText.Dynamic("1920×1080"))),
                                        selectedKey = "1080", onOptionSelected = {}, onBack = back,
                                    )
                                    else -> Text("Broadcast settings")
                                }
                            }
                        })
                    },
                )
            }
        }
        fun assertCaptureContinues() {
            val baseline = frames.get()
            compose.waitUntil(20_000) { frames.get() >= baseline + 5 }
            compose.runOnIdle {
                assertEquals(1, starts.get())
                assertEquals(0, disposals.get())
            }
        }
        try {
            assertCaptureContinues()
            compose.runOnIdle { routes.add(SettingsRoute) }
            compose.onNodeWithText("user@example.com").assertIsDisplayed()
            assertCaptureContinues()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.cacheDir, "settings-layer.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            compose.onNodeWithText(context.getString(R.string.label_camera_resolution)).performClick()
            assertCaptureContinues()
            compose.onNodeWithText("1920×1080").performClick()
            assertCaptureContinues()
            compose.onNodeWithText(context.getString(R.string.settings_camera_audio))
                .performScrollTo().performClick()
            assertCaptureContinues()
            compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
            compose.onNodeWithText(context.getString(R.string.settings_broadcast))
                .performScrollTo().performClick()
            assertCaptureContinues()
            compose.runOnIdle { back() }
            compose.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()
            assertCaptureContinues()
            compose.runOnIdle { showStudio.value = false }
            compose.runOnIdle { assertEquals(1, disposals.get()) }
        } finally {
            compose.runOnIdle { showStudio.value = false }
            analyzer.close()
        }
    }
}
