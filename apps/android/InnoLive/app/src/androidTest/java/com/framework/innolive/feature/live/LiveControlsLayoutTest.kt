package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
            0.01f,
        )
        assertEquals(
            ((container.left + center.left) / 2f).value,
            ((leading.left + leading.right) / 2f).value,
            0.01f,
        )
        assertEquals(
            ((center.right + container.right) / 2f).value,
            ((trailing.left + trailing.right) / 2f).value,
            0.01f,
        )
    }

    @Test
    fun primaryControlsKeepTheirPositionsWhenBroadcastBecomesPrepared() {
        var prepared by mutableStateOf(false)
        compose.setContent {
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .height(220.dp),
            ) {
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    BalancedLiveControls(
                        leading = { Box(Modifier.size(48.dp).testTag("face-control")) },
                        center = {
                            Box(
                                Modifier
                                    .width(240.dp)
                                    .height(57.dp)
                                    .testTag("broadcast-control"),
                            )
                        },
                        trailing = { Box(Modifier.size(48.dp).testTag("anonymization-control")) },
                    )
                    StableBroadcastFeedback {
                        if (prepared) Box(Modifier.size(48.dp).testTag("cancel-control"))
                    }
                }
            }
        }

        val before = listOf(
            bounds("face-control"),
            bounds("broadcast-control"),
            bounds("anonymization-control"),
        )
        compose.runOnIdle { prepared = true }
        val after = listOf(
            bounds("face-control"),
            bounds("broadcast-control"),
            bounds("anonymization-control"),
        )

        before.zip(after).forEach { (beforeBounds, afterBounds) ->
            assertEquals(beforeBounds.left.value, afterBounds.left.value, 0.01f)
            assertEquals(beforeBounds.top.value, afterBounds.top.value, 0.01f)
        }
    }

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
}
