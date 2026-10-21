package com.framework.innolive.feature.live

import android.Manifest
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class FaceSelectionBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 얼굴 선택 중 뒤로 가기는 선택만 끝내고 방송 화면에 머뭅니다. */
    @Test fun backDuringFaceSelectionCancelsSelectionAndStaysOnLiveScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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
                )
            }
        }
        val start = hasText(label(R.string.quick_face_select_start)) and hasClickAction()
        val cancel = hasText(label(R.string.quick_face_select_cancel)) and hasClickAction()
        compose.waitUntil(10_000) { compose.onAllNodes(start and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        // 캡처에 카메라 미리보기가 보이도록 카메라가 열릴 때까지 잠시 기다립니다.
        SystemClock.sleep(3_000)
        compose.onNode(start).performClick()
        compose.onNode(cancel).assertIsDisplayed()
        capture("face-selection-back-1-selecting")

        Espresso.pressBackUnconditionally()
        SystemClock.sleep(1_000)
        capture("face-selection-back-2-after-back")
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
        compose.onNode(start).assertIsDisplayed()
        compose.onNode(cancel).assertDoesNotExist()
    }

    private fun label(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
