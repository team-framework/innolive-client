package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BroadcastActionButtonsLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun preparedInformationButtonOpensStartAndCancelActions() {
        compose.setContent {
            MaterialTheme {
                BroadcastActionControls(
                    presentation = buildLiveScreenPresentation(
                        connectionState = WebRtcConnectionState.CONNECTED,
                        broadcastState = BroadcastState.PREPARED,
                        selectedPlatform = "YouTube",
                        broadcastStatus = "방송 준비 완료",
                    ),
                    onBroadcastAction = {},
                    leading = { Box(Modifier.size(48.dp).testTag("face-control")) },
                    trailing = { Box(Modifier.size(48.dp).testTag("anonymization-control")) },
                )
            }
        }

        val infoBounds = compose.onNodeWithText("방송 준비 완료").getUnclippedBoundsInRoot()
        val faceBounds = compose.onNodeWithTag("face-control").getUnclippedBoundsInRoot()
        val anonymizationBounds = compose.onNodeWithTag("anonymization-control").getUnclippedBoundsInRoot()

        val startCenterY = (infoBounds.top.value + infoBounds.bottom.value) / 2f
        val faceCenterY = (faceBounds.top.value + faceBounds.bottom.value) / 2f
        val anonymizationCenterY = (anonymizationBounds.top.value + anonymizationBounds.bottom.value) / 2f

        assertEquals(startCenterY, faceCenterY, 0.5f)
        assertEquals(startCenterY, anonymizationCenterY, 0.5f)
    }

    @Test
    fun preparedDialogOffersStartAndPreparationCancellationWithoutLongPress() {
        compose.setContent {
            MaterialTheme {
                val presentation = buildLiveScreenPresentation(
                    WebRtcConnectionState.CONNECTED,
                    BroadcastState.PREPARED,
                    "YouTube",
                    "방송 준비 완료",
                )
                var open by remember { mutableStateOf(false) }
                BroadcastActionControls(
                    presentation = presentation,
                    onBroadcastAction = { open = true },
                    leading = {},
                    trailing = {},
                )
                if (open) {
                    BroadcastActionDialog(
                        presentation = presentation,
                        onDismiss = { open = false },
                        onGoLive = {},
                        onCancelPreparation = {},
                        onPauseOrResume = {},
                        onStop = {},
                    )
                }
            }
        }

        compose.onNodeWithText("방송 준비 완료").performClick()
        compose.onNodeWithText("방송 시작").assertExists()
        compose.onNodeWithText("방송 준비 취소").assertExists()
    }

    @Test
    fun liveInformationButtonOpensPauseAndStopActions() {
        compose.setContent {
            MaterialTheme {
                val presentation = buildLiveScreenPresentation(
                    WebRtcConnectionState.CONNECTED,
                    BroadcastState.LIVE,
                    "YouTube",
                    "YouTube 방송 중",
                )
                var open by remember { mutableStateOf(false) }
                BroadcastActionControls(
                    presentation = presentation,
                    onBroadcastAction = { open = true },
                    leading = {},
                    trailing = {},
                )
                if (open) {
                    BroadcastActionDialog(
                        presentation = presentation,
                        onDismiss = { open = false },
                        onGoLive = {},
                        onCancelPreparation = {},
                        onPauseOrResume = {},
                        onStop = {},
                    )
                }
            }
        }

        compose.onNodeWithText("방송 중").performClick()
        compose.onNodeWithText("방송 일시 중지").assertExists()
        compose.onNodeWithText("방송 종료").assertExists()
    }
}
