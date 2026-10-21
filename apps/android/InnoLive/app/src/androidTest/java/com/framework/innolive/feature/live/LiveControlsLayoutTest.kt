package com.framework.innolive.feature.live

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveControlsLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cameraToolsFormCenteredRightRailInPortrait() {
        showCameraTools(360.dp, 800.dp)
        assertRightRail()
    }

    @Test
    fun cameraToolsStayReachableInShortViewport() {
        showCameraTools(360.dp, 340.dp)
        assertRightRail()
    }

    @Test
    fun cameraToolsScrollWhenViewportIsTooShort() {
        showCameraTools(360.dp, 120.dp)
        compose.onNodeWithText(compose.activity.getString(R.string.quick_face_select_start))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun broadcastButtonStaysInPlaceWhenFeedbackAppears() {
        var prepared by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(260.dp).background(Color.Black).testTag("container")) {
                    Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                        BroadcastActionControls(
                            presentation = buildLiveScreenPresentation(
                                connectionState = WebRtcConnectionState.CONNECTED,
                                broadcastState = if (prepared) BroadcastState.PREPARED else BroadcastState.IDLE,
                                selectedPlatform = "YouTube",
                                broadcastStatus = if (prepared) "방송 준비 완료" else "",
                            ),
                            onBroadcastAction = {},
                        )
                        // 준비 뒤 두 줄을 넘을 만큼 긴 오류 문구가 나타나도 버튼 위치는 그대로이고 문구는 두 줄 안에 다 보입니다.
                        StableBroadcastFeedback(
                            text = if (prepared) LONG_FEEDBACK else null,
                            isError = true,
                            modifier = Modifier.testTag("feedback"),
                        )
                    }
                }
            }
        }

        val before = compose.onNodeWithText(compose.activity.getString(R.string.action_prepare_broadcast))
            .getUnclippedBoundsInRoot()
        compose.runOnIdle { prepared = true }
        val after = compose.onNode(
            hasText(compose.activity.getString(R.string.broadcast_state_prepared)) and hasClickAction(),
        ).getUnclippedBoundsInRoot()
        val container = compose.onNodeWithTag("container").getUnclippedBoundsInRoot()

        assertEquals(before.top.value, after.top.value, 0.5f)
        assertEquals(((container.left + container.right) / 2f).value, ((after.left + after.right) / 2f).value, 0.5f)
        val feedback = compose.onNodeWithTag("feedback").getUnclippedBoundsInRoot()
        assertTrue(container.bottom - after.bottom <= (feedback.bottom - feedback.top) + 8.dp)
        assertTrue(feedback.bottom <= container.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(LONG_FEEDBACK).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertTrue("문구 ${layout.lineCount}줄", layout.lineCount <= 2)
        assertFalse("문구 잘림 ${layout.lineCount}줄", layout.hasVisualOverflow || layout.isLineEllipsized(layout.lineCount - 1))
        capture("broadcast-feedback-two-lines")
    }

    @Test
    fun feedbackLeavesNoGapWhenNotReserved() {
        compose.setContent {
            MaterialTheme {
                StableBroadcastFeedback(text = null, reserveHeight = false, modifier = Modifier.testTag("feedback"))
            }
        }

        compose.onNodeWithTag("feedback").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun showCameraTools(width: Dp, height: Dp) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width).height(height).testTag("container")) {
                    LiveCameraTools(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                        canManageFace = true,
                        onOpenVideoControls = {},
                        onOpenFaceManagement = {},
                    )
                }
            }
        }
    }

    private fun assertRightRail() {
        val labels = listOf(R.string.video_controls_title, R.string.face_management, R.string.quick_face_select_start)
        val controls = labels.map { id ->
            compose.onNode(hasText(compose.activity.getString(id)) and hasClickAction())
                .getUnclippedBoundsInRoot()
        }
        val container = compose.onNodeWithTag("container").getUnclippedBoundsInRoot()
        val centerX = (controls.first().left + controls.first().right) / 2f

        controls.forEach { bounds ->
            assertEquals(centerX.value, ((bounds.left + bounds.right) / 2f).value, 0.5f)
            assertTrue(bounds.right <= container.right - 16.dp)
            assertTrue(bounds.top >= container.top)
            assertTrue(bounds.bottom <= container.bottom)
        }
        controls.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bottom <= lower.top) }
        assertEquals(
            ((container.top + container.bottom) / 2f).value,
            ((controls.first().top + controls.last().bottom) / 2f).value,
            0.5f,
        )
    }
}

// 버튼 아래에 나오는 문구 중 가장 긴 error_youtube_channel_missing과 같은 길이입니다.
private const val LONG_FEEDBACK =
    "선택한 Google 계정에 YouTube 채널이 없습니다. 채널을 만든 뒤 다시 시도하거나 다른 계정을 선택해 주세요."
