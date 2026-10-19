package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import org.junit.Assert.assertEquals
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
    fun viewerChatButtonJoinsCameraToolsOnlyWhenChatIsAvailable() {
        var canOpenViewerChat by mutableStateOf(false)
        var opened = 0
        showCameraTools(360.dp, 800.dp, canOpenViewerChat = { canOpenViewerChat }, onOpenViewerChat = { opened++ })
        val chat = hasText(compose.activity.getString(R.string.viewer_chat_title)) and hasClickAction()
        compose.onNode(chat).assertDoesNotExist()

        compose.runOnIdle { canOpenViewerChat = true }
        compose.onNode(chat).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        val lastTool = compose.onNode(
            hasText(compose.activity.getString(R.string.quick_face_select_start)) and hasClickAction(),
        ).getUnclippedBoundsInRoot()
        val button = compose.onNode(chat).getUnclippedBoundsInRoot()
        val container = compose.onNodeWithTag("container").getUnclippedBoundsInRoot()
        assertEquals(((lastTool.left + lastTool.right) / 2f).value, ((button.left + button.right) / 2f).value, 0.5f)
        assertTrue(lastTool.bottom <= button.top)
        assertTrue(button.right <= container.right - 16.dp)

        compose.runOnIdle { canOpenViewerChat = false }
        compose.onNode(chat).assertDoesNotExist()
    }

    @Test
    fun broadcastButtonStaysCenteredWhenBroadcastBecomesPrepared() {
        var prepared by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(260.dp).testTag("container")) {
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
                        StableBroadcastFeedback { Text(if (prepared) "방송 준비 완료" else "") }
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
        assertTrue(container.bottom - after.bottom <= 36.dp)
    }

    private fun showCameraTools(
        width: Dp,
        height: Dp,
        canOpenViewerChat: () -> Boolean = { false },
        onOpenViewerChat: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width).height(height).testTag("container")) {
                    LiveCameraTools(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                        canManageFace = true,
                        onOpenVideoControls = {},
                        onOpenFaceManagement = {},
                        canOpenViewerChat = canOpenViewerChat(),
                        onOpenViewerChat = onOpenViewerChat,
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
