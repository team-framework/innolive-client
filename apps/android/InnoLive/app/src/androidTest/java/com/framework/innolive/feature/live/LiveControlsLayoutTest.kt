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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveControlsLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun whiteControlsFormCenteredRightRailInPortrait() {
        showSideControls(360.dp, 800.dp)
        assertRightRail()
    }

    @Test
    fun whiteControlsStayReachableInShortViewport() {
        showSideControls(360.dp, 320.dp)
        assertRightRail()
    }

    @Test
    fun whiteControlsScrollWhenViewportIsTooShort() {
        showSideControls(360.dp, 220.dp)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.anonymization_enable))
            .performScrollTo()
            .assertIsDisplayed()
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

    private fun showSideControls(width: Dp, height: Dp) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width).height(height).testTag("container")) {
                    LiveSideControls(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                        canOpenSettings = true,
                        canSwitchCamera = true,
                        canManageFace = true,
                        onOpenSettings = {},
                        onOpenVideoControls = {},
                        onSwitchCamera = {},
                        onOpenFaceManagement = {},
                        anonymizationState = AnonymizationControlsState(
                            label = UiText.Resource(R.string.anonymization_value_off),
                            actionDescription = UiText.Resource(R.string.anonymization_enable),
                            selectedEnabled = false,
                            canChange = true,
                        ),
                        onAnonymizationSelect = {},
                    )
                }
            }
        }
    }

    private fun assertRightRail() {
        val labels = listOf(
            R.string.content_description_settings,
            R.string.video_controls_title,
            R.string.content_description_switch_camera,
            R.string.face_management,
            R.string.anonymization_enable,
        )
        val controls = labels.map { id ->
            compose.onNodeWithContentDescription(compose.activity.getString(id))
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
            (((controls.first().top + controls.first().bottom) / 2f +
                (controls.last().top + controls.last().bottom) / 2f) / 2f).value,
            0.5f,
        )
    }
}
