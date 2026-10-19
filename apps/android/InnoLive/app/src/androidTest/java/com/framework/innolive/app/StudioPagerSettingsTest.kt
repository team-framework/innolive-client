package com.framework.innolive.app

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.CameraPreview
import com.framework.innolive.feature.live.LiveSettingsPage
import com.framework.innolive.feature.live.LiveStudioPager
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

class StudioPagerSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swipingBetweenPagesAndNestedSettingsKeepCameraFramesAndStudioComposition() {
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
        val closeSettings: () -> Unit = { while (routes.last() != LiveRoute) routes.removeLastOrNull() }
        compose.setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                if (!showStudio.value) return@MyApplicationTheme
                val settingsScreen: @androidx.compose.runtime.Composable () -> Unit = {
                    SettingsScreen(SettingsScreenProps(
                        onBack = closeSettings,
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
                }
                LiveStudioPager(
                    pagerState = rememberPagerState(initialPage = 1) { 3 },
                    settings = LiveSettingsPage(
                        isOpen = routes.last() != LiveRoute,
                        canLeaveBySwipe = routes.last() == LiveRoute || routes.last() == SettingsRoute,
                        onOpen = { if (routes.last() == LiveRoute) routes.add(SettingsRoute) },
                        onClose = closeSettings,
                        content = {
                            NavDisplay(backStack = routes, onBack = back, entryProvider = { route ->
                                NavEntry(route) {
                                    when (route) {
                                        LiveRoute, SettingsRoute -> settingsScreen()
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
                    ),
                    canSwipe = true,
                    pinnedToHome = false,
                    preview = {
                        DisposableEffect(Unit) {
                            starts.incrementAndGet()
                            onDispose { disposals.incrementAndGet() }
                        }
                        CameraPreview(CameraLensFacing.BACK, null, frameAnalyzer = analyzer,
                            showAdjustedColorPreview = false)
                    },
                    topBar = { Text("top bar") },
                    cameraPage = { Text("camera tools") },
                    homePage = { Text("home controls") },
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
        fun pageDescription(page: Int, nameRes: Int) =
            context.getString(R.string.live_page_indicator, page, 3, context.getString(nameRes))
        try {
            assertCaptureContinues()
            compose.onNodeWithContentDescription(pageDescription(2, R.string.live_page_home)).assertIsDisplayed()
            // 기본 화면에서 왼쪽으로 넘기면 설정 페이지가 열리고 백스택에도 설정 화면이 쌓입니다.
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { routes.last() == SettingsRoute }
            compose.onNodeWithText("user@example.com").assertIsDisplayed()
            assertCaptureContinues()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.cacheDir, "settings-page.png").outputStream().use {
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
            // 닫기는 설정 화면을 백스택에서 빼고 기본 화면으로 돌아갑니다.
            compose.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()
            compose.waitUntil(5_000) { routes.toList() == listOf(LiveRoute) }
            compose.onNodeWithContentDescription(pageDescription(2, R.string.live_page_home)).assertIsDisplayed()
            assertCaptureContinues()
            // 기본 화면에서 오른쪽으로 넘기면 카메라 화면이고, 뒤로 가기는 기본 화면으로 돌아옵니다.
            compose.onRoot().performTouchInput { swipeRight() }
            compose.onNodeWithContentDescription(pageDescription(1, R.string.live_page_camera)).assertIsDisplayed()
            assertCaptureContinues()
            Espresso.pressBack()
            compose.onNodeWithContentDescription(pageDescription(2, R.string.live_page_home)).assertIsDisplayed()
            compose.runOnIdle { assertEquals(listOf<AppRoute>(LiveRoute), routes.toList()) }
            compose.runOnIdle { showStudio.value = false }
            compose.runOnIdle { assertEquals(1, disposals.get()) }
        } finally {
            compose.runOnIdle { showStudio.value = false }
            analyzer.close()
        }
    }
}
