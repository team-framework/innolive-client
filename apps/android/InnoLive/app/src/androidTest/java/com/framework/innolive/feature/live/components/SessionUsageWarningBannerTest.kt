package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.framework.innolive.R
import org.junit.Rule
import org.junit.Test

class SessionUsageWarningBannerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clearedNoticeKeepsLastMessageDuringExitAnimation() {
        var noticeCode by mutableStateOf<String?>("broadcast_limit_10m")
        rule.setContent { SessionUsageWarningBanner(noticeCode = noticeCode, onDismissRequest = {}) }
        val message = rule.activity.getString(R.string.session_notice_broadcast_limit_10m)
        rule.onNodeWithText(message).assertExists()

        rule.mainClock.autoAdvance = false
        noticeCode = null
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithText(message).assertExists()

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun replacedNoticeShowsTheNewMessage() {
        var noticeCode by mutableStateOf<String?>("broadcast_limit_30m")
        rule.setContent { SessionUsageWarningBanner(noticeCode = noticeCode, onDismissRequest = {}) }
        rule.onNodeWithText(rule.activity.getString(R.string.session_notice_broadcast_limit_30m)).assertExists()

        noticeCode = "monthly_usage_80"
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.session_notice_monthly_usage_80)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.session_notice_broadcast_limit_30m)).assertDoesNotExist()
    }
}
