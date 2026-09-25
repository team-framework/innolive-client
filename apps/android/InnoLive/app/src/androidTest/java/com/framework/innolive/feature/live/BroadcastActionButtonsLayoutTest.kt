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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BroadcastActionButtonsLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reconnectingPreparedBroadcastAllowsCancellationButNotStart() {
        compose.setContent {
            MaterialTheme {
                BroadcastActionDialog(
                    presentation = buildLiveScreenPresentation(
                        WebRtcConnectionState.RECONNECTING, BroadcastState.PREPARED, "YouTube", "복구 중",
                    ),
                    onDismiss = {}, onGoLive = {}, onCancelPreparation = {},
                    onPauseOrResume = {}, onStop = {},
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.action_start_broadcast))
            .assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.action_cancel_preparation))
            .assertIsEnabled()
    }

    @Test
    fun reconnectingPausedBroadcastAllowsStopButNotResume() {
        compose.setContent {
            MaterialTheme {
                BroadcastActionDialog(
                    presentation = buildLiveScreenPresentation(
                        WebRtcConnectionState.RECONNECTING, BroadcastState.PAUSED, "YouTube", "복구 중",
                    ),
                    onDismiss = {}, onGoLive = {}, onCancelPreparation = {},
                    onPauseOrResume = {}, onStop = {},
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.action_resume_broadcast))
            .assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.action_stop_broadcast))
            .assertIsEnabled()
    }

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

        val infoBounds = compose.onNodeWithText(
            compose.activity.getString(R.string.broadcast_state_prepared),
        ).getUnclippedBoundsInRoot()
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

        compose.onNodeWithText(compose.activity.getString(R.string.broadcast_state_prepared))
            .performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.action_start_broadcast))
            .assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.action_cancel_preparation))
            .assertExists()
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

        compose.onNodeWithText(compose.activity.getString(R.string.broadcast_state_live))
            .performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.action_pause_broadcast))
            .assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.action_stop_broadcast))
            .assertExists()
    }
}
