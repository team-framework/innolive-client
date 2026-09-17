package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BroadcastActionButtonsLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cancelPreparationStaysAboveAlignedLiveControls() {
        compose.setContent {
            MaterialTheme {
                BroadcastActionControls(
                    presentation = buildLiveScreenPresentation(
                        connectionState = WebRtcConnectionState.CONNECTED,
                        broadcastState = BroadcastState.PREPARED,
                        selectedPlatform = "YouTube",
                        broadcastStatus = "방송 준비 완료",
                    ),
                    onCancelPreparation = {},
                    onBroadcastAction = {},
                    leading = { Box(Modifier.size(48.dp).testTag("face-control")) },
                    trailing = { Box(Modifier.size(48.dp).testTag("anonymization-control")) },
                )
            }
        }

        val cancelBounds = compose.onNodeWithText("방송 준비 취소").getUnclippedBoundsInRoot()
        val startBounds = compose.onNodeWithText("라이브 시작").getUnclippedBoundsInRoot()
        val faceBounds = compose.onNodeWithTag("face-control").getUnclippedBoundsInRoot()
        val anonymizationBounds = compose.onNodeWithTag("anonymization-control").getUnclippedBoundsInRoot()

        assertTrue(
            "방송 준비 취소 버튼은 라이브 시작 버튼 위에 있어야 합니다.",
            cancelBounds.bottom <= startBounds.top,
        )
        val startCenterY = (startBounds.top.value + startBounds.bottom.value) / 2f
        val faceCenterY = (faceBounds.top.value + faceBounds.bottom.value) / 2f
        val anonymizationCenterY = (anonymizationBounds.top.value + anonymizationBounds.bottom.value) / 2f

        assertEquals(startCenterY, faceCenterY, 0.5f)
        assertEquals(startCenterY, anonymizationCenterY, 0.5f)
    }
}
