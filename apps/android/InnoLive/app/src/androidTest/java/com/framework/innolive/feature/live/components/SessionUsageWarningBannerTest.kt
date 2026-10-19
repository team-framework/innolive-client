package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.framework.innolive.R
import org.junit.Rule
import org.junit.Test

class SessionUsageWarningBannerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun hidingNoticeAnimatesOutWithoutCrashing() {
        val code = mutableStateOf<String?>("monthly_usage_80")
        compose.setContent {
            MaterialTheme { SessionUsageWarningBanner(noticeCode = code.value, onDismissRequest = {}) }
        }
        val message = compose.activity.getString(R.string.session_notice_monthly_usage_80)
        compose.onNodeWithText(message).assertIsDisplayed()

        // 사라지는 애니메이션 중에도 다시 그려지며, 이때 마지막 문구로 그려야 합니다.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { code.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(message).assertExists()
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithText(message).assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test fun replacedNoticeShowsTheNewMessage() {
        val code = mutableStateOf<String?>("broadcast_limit_30m")
        compose.setContent {
            MaterialTheme { SessionUsageWarningBanner(noticeCode = code.value, onDismissRequest = {}) }
        }
        val previous = compose.activity.getString(R.string.session_notice_broadcast_limit_30m)
        compose.onNodeWithText(previous).assertIsDisplayed()

        compose.runOnIdle { code.value = "monthly_usage_80" }
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.session_notice_monthly_usage_80)).assertExists()
        compose.onNodeWithText(previous).assertDoesNotExist()
    }
}
