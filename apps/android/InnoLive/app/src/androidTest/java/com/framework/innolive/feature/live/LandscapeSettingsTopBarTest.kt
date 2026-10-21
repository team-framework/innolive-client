package com.framework.innolive.feature.live

import android.Manifest
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.settings.SettingsScreen
import com.framework.innolive.feature.settings.SettingsScreenProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File

class LandscapeSettingsTopBarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** 방송 화면을 가로로 띄우도록 화면을 띄우기 전에 돌리고, 끝나면 세로로 되돌립니다. */
    private val landscape = object : ExternalResource() {
        override fun before() {
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            SystemClock.sleep(1_000)
        }

        override fun after() {
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(landscape).around(compose)

    private val settingsOpen = mutableStateOf(true)
    private var accountOpened = 0

    /** 가로 화면 설정 페이지에서 숨긴 페이지 탭 자리를 눌러도 설정 창이 받고 설정 페이지에 머뭅니다. */
    @Test fun hiddenTopBarDoesNotTakeTapsOverSettingsPanel() {
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
        lateinit var session: WebRtcSessionViewModel
        compose.runOnIdle { session = ViewModelProvider(compose.activity)[WebRtcSessionViewModel::class.java] }
        compose.setContent {
            MaterialTheme {
                LiveScreen(
                    LiveScreenProps(
                        cameraLensFacing = CameraLensFacing.BACK,
                        cameraResolution = null,
                        broadcastSettings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22"),
                        onBroadcastSettingsChanged = {},
                        youtubeChannelTitle = "검증 채널",
                        hasYouTubeAccount = true,
                        youtubeAccountStatus = "",
                        isYouTubeReconnectRequired = false,
                        isYouTubeAccountActionInProgress = false,
                        isYouTubeConnectEnabled = true,
                        onConnectYouTube = {},
                        onRefreshAccessToken = { error("검증에서는 연결하지 않습니다") },
                        onOpenSettings = {},
                    ),
                    session,
                    settings = LiveSettingsPage(
                        isOpen = settingsOpen.value,
                        canLeaveBySwipe = true,
                        onOpen = { settingsOpen.value = true },
                        onClose = { settingsOpen.value = false },
                        content = {
                            SettingsScreen(
                                SettingsScreenProps(
                                    onBack = { settingsOpen.value = false },
                                    onOpenBroadcastSettings = {},
                                    profileName = "InnoLive User",
                                    profileEmail = "user@example.com",
                                    onLogout = {},
                                    onOpenAccountSettings = { accountOpened++ },
                                ),
                            )
                        },
                    ),
                )
            }
        }
        compose.waitForIdle()
        // 캡처에 카메라 미리보기가 보이도록 카메라가 열릴 때까지 잠시 기다립니다.
        SystemClock.sleep(3_000)
        capture("landscape-settings-1-open")

        // 숨긴 페이지 탭 가운데(기본 화면 탭)가 있던 자리를 누릅니다.
        compose.onRoot().performTouchInput {
            click(Offset(width / 2f, (LiveStudioTopInset - 26.dp).toPx()))
        }
        compose.waitForIdle()
        SystemClock.sleep(500)
        capture("landscape-settings-2-after-tap")
        assertTrue(settingsOpen.value)
        // 가로 화면 설정 페이지는 상단 바를 숨기므로 페이지 탭도 화면 낭독기에 보이지 않습니다.
        val pageTabs = hasContentDescription(label(R.string.live_page_home)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        assertEquals(0, compose.onAllNodes(pageTabs).fetchSemanticsNodes().size)
    }

    private fun label(id: Int): String = instrumentation.targetContext.getString(id)

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
