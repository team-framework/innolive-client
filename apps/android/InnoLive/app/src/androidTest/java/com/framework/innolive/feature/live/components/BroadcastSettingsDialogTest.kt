package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.directBroadcastModeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 방송 설정 창이 고른 플랫폼마다 탭을 두고, 준비할 때 입력이 빠진 탭으로 옮기는지 확인합니다. */
class BroadcastSettingsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var prepared = 0

    private fun show(providers: Set<BroadcastProvider>, youtube: BroadcastSettings) {
        compose.setContent {
            MaterialTheme {
                BroadcastSettingsDialog(
                    planState = directBroadcastModeState(
                        plan = DirectBroadcastPlan(providers = providers),
                        planUsage = null,
                        accountConnected = { true },
                        locked = false,
                    ),
                    onPlanChanged = {},
                    onOpenPlan = {},
                    showResolution = true,
                    youtube = YouTubeSettingsInput(
                        settings = youtube, onChanged = {}, serverError = null, accountStatus = "채널",
                        hasAccount = true, reconnectRequired = false, accountActionInProgress = false,
                        connectEnabled = true, onConnect = {},
                    ),
                    chzzk = ChzzkSettingsInput(
                        settings = ChzzkBroadcastSettings("치지직 방송"), accountLabel = "치지직 채널",
                        canConnect = true, canDisconnect = true, isBusy = false, onChanged = {},
                        onRefreshAccount = {}, onConnect = {}, onDisconnect = {}, onSearch = { emptyList() },
                    ),
                    canPrepare = true,
                    onPrepare = { prepared++ },
                    onDismiss = {},
                    initialTab = BroadcastProvider.CHZZK,
                )
            }
        }
    }

    private fun label(id: Int) = compose.activity.getString(id)
    private val prepareButton get() = compose.onNode(
        hasText(label(R.string.action_prepare_broadcast)) and hasClickAction() and hasAnyAncestor(isDialog()),
    )

    @Test fun bothPlatformsAreEditedThroughTabsAndMissingInputMovesToItsTab() {
        show(setOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK), BroadcastSettings("", "", "private", null, "22"))
        // 치지직 탭으로 열려 치지직 입력만 보입니다.
        compose.onNodeWithText(label(R.string.chzzk_label_category_search)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.label_broadcast_privacy)).assertDoesNotExist()
        // YouTube 공개 대상이 비어 있으므로 준비하지 않고 YouTube 탭으로 옮깁니다.
        prepareButton.performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.label_broadcast_privacy)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.validation_audience)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, prepared) }
        compose.onNodeWithText(label(R.string.direct_mode_resolution_title)).performScrollTo().assertIsDisplayed()
    }

    @Test fun singlePlatformHasNoTabsAndPreparesWhenValid() {
        show(setOf(BroadcastProvider.YOUTUBE), BroadcastSettings("방송", "설명", "private", false, "22"))
        compose.onNodeWithText(label(R.string.label_broadcast_privacy)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.chzzk_label_category_search)).assertDoesNotExist()
        prepareButton.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, prepared) }
    }
}
