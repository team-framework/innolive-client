package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BroadcastActionButtonsLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cancelPreparationIsDisplayedAboveStartButton() {
        compose.setContent {
            MaterialTheme {
                BroadcastActionButtons(
                    presentation = buildLiveScreenPresentation(
                        connectionState = WebRtcConnectionState.CONNECTED,
                        broadcastState = BroadcastState.PREPARED,
                        selectedPlatform = "YouTube",
                        broadcastStatus = "방송 준비 완료",
                    ),
                    onCancelPreparation = {},
                    onBroadcastAction = {},
                )
            }
        }

        val cancelBounds = compose.onNodeWithText("방송 준비 취소").getUnclippedBoundsInRoot()
        val startBounds = compose.onNodeWithText("라이브 시작").getUnclippedBoundsInRoot()

        assertTrue(
            "방송 준비 취소 버튼은 라이브 시작 버튼 위에 있어야 합니다.",
            cancelBounds.bottom <= startBounds.top,
        )
    }
}
