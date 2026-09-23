package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
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
    fun controlsStayCenteredAtGalaxyS25Width() {
        compose.setContent {
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .height(80.dp)
                    .testTag("container"),
            ) {
                BalancedLiveControls(
                    leading = {
                        Box(Modifier.size(48.dp).testTag("leading"))
                    },
                    center = {
                        Box(
                            Modifier
                                .width(240.dp)
                                .height(57.dp)
                                .testTag("center"),
                        )
                    },
                    trailing = {
                        Box(Modifier.size(48.dp).testTag("trailing"))
                    },
                )
            }
        }

        val container = bounds("container")
        val leading = bounds("leading")
        val center = bounds("center")
        val trailing = bounds("trailing")

        assertEquals(
            ((container.left + container.right) / 2f).value,
            ((center.left + center.right) / 2f).value,
            0.5f,
        )
        assertEquals(
            (leading.left - container.left).value,
            (container.right - trailing.right).value,
            0.5f,
        )
        assertTrue(leading.right <= center.left)
        assertTrue(center.right <= trailing.left)
    }

    @Test
    fun primaryControlsKeepTheirPositionsWhenBroadcastBecomesPrepared() {
        var prepared by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .height(260.dp)
                        .testTag("container"),
                ) {
                    Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                        BroadcastActionControls(
                            presentation = buildLiveScreenPresentation(
                                connectionState = WebRtcConnectionState.CONNECTED,
                                broadcastState = if (prepared) BroadcastState.PREPARED else BroadcastState.IDLE,
                                selectedPlatform = "YouTube",
                                broadcastStatus = if (prepared) "방송 준비 완료" else "",
                            ),
                            onBroadcastAction = {},
                            leading = { Box(Modifier.size(48.dp).testTag("face-control")) },
                            trailing = { Box(Modifier.size(48.dp).testTag("anonymization-control")) },
                        )
                        StableBroadcastFeedback {
                            Text(if (prepared) "방송 준비 완료" else "")
                        }
                    }
                }
            }
        }

        val before = listOf(
            bounds("face-control"),
            compose.onNodeWithText(compose.activity.getString(R.string.action_prepare_broadcast))
                .getUnclippedBoundsInRoot(),
            bounds("anonymization-control"),
        )
        compose.runOnIdle { prepared = true }
        val after = listOf(
            bounds("face-control"),
            compose.onNode(
                hasText(compose.activity.getString(R.string.broadcast_state_prepared)) and hasClickAction(),
            ).getUnclippedBoundsInRoot(),
            bounds("anonymization-control"),
        )

        before.zip(after).forEach { (beforeBounds, afterBounds) ->
            assertEquals(beforeBounds.top.value, afterBounds.top.value, 0.5f)
            assertEquals(
                ((beforeBounds.left + beforeBounds.right) / 2f).value,
                ((afterBounds.left + afterBounds.right) / 2f).value,
                0.5f,
            )
        }
        val bottomGap = bounds("container").bottom - after[0].bottom
        assertTrue("얼굴 관리 버튼이 하단에서 너무 멀어지지 않아야 합니다.", bottomGap <= 36.dp)
    }

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
}
