package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.directBroadcastModeState
import com.framework.innolive.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 치지직 계정을 쓸 수 없을 때 방송 설정의 플랫폼 선택이 비활성 이유를 보여 주는지 확인합니다. */
class BroadcastPlanSelectorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val connected = mutableListOf<BroadcastProvider>()

    private fun show(checking: () -> Boolean, issue: UiText?) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                BroadcastPlanSelector(
                    state = directBroadcastModeState(
                        plan = DirectBroadcastPlan(),
                        planUsage = null,
                        accountConnected = { it == BroadcastProvider.YOUTUBE },
                        locked = false,
                    ),
                    onPlanChanged = {},
                    onOpenPlan = {},
                    accountChecking = { checking() },
                    accountIssue = { provider -> issue.takeIf { provider == BroadcastProvider.CHZZK } },
                    onConnectAccount = { connected += it },
                )
            }
        }
    }

    private fun label(id: Int) = compose.activity.getString(id)

    @Test fun failedAccountLookupShowsItsReasonAfterGrace() {
        show(checking = { false }, issue = UiText.Resource(R.string.chzzk_account_check_failed))
        compose.onNodeWithText(label(R.string.chzzk_account_check_failed)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(label(R.string.chzzk_account_check_failed)).assertIsDisplayed()
        compose.onNodeWithText("CHZZK").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.direct_mode_account_required)).assertDoesNotExist()
    }

    @Test fun lookupInProgressHidesReasonUntilItEnds() {
        var checking by mutableStateOf(true)
        show(checking = { checking }, issue = null)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(label(R.string.direct_mode_account_required)).assertDoesNotExist()
        checking = false
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(label(R.string.direct_mode_account_required)).assertIsDisplayed()
    }

    @Test fun unconnectedPlatformOffersConnectAfterLookup() {
        var checking by mutableStateOf(true)
        show(checking = { checking }, issue = null)
        compose.mainClock.advanceTimeBy(2_000)
        // 조회 중에는 연결 버튼을 띄우지 않습니다.
        compose.onNodeWithText(label(R.string.action_connect)).assertDoesNotExist()
        checking = false
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(label(R.string.action_connect)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(BroadcastProvider.CHZZK), connected) }
    }
}
