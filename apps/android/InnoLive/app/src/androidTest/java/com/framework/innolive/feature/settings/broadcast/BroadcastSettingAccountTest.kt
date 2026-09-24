package com.framework.innolive.feature.settings.broadcast

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BroadcastSettingAccountTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cachedChannelCanReconnectAfterServerCheckFails() {
        val isChecking = mutableStateOf(true)
        val isVerified = mutableStateOf(false)
        var reconnectClicks = 0

        composeRule.setContent {
            MaterialTheme {
                BroadcastSetting(
                    props = testProps(
                        channelTitle = "저장된 채널",
                        hasVerifiedAccount = isVerified.value,
                        isChecking = isChecking.value,
                        onConnect = { reconnectClicks++ },
                    ),
                )
            }
        }

        composeRule.onNodeWithText("저장된 채널").assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.action_reconnect),
        ).assertIsNotEnabled()

        composeRule.runOnIdle { isChecking.value = false }
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.action_reconnect),
        ).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, reconnectClicks) }

        composeRule.runOnIdle { isVerified.value = true }
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.action_reconnect),
        ).assertDoesNotExist()
    }

    private fun testProps(
        channelTitle: String?,
        hasVerifiedAccount: Boolean,
        isChecking: Boolean,
        onConnect: () -> Unit,
    ) = BroadcastSettingProps(
        onBack = {},
        selectedPlatform = "YouTube",
        onOpenPlatformOptions = {},
        title = "방송 제목",
        onTitleChanged = {},
        description = "방송 설명",
        onDescriptionChanged = {},
        selectedPrivacy = "비공개",
        onOpenPrivacyOptions = {},
        selectedAudience = "선택 필요",
        onOpenAudienceOptions = {},
        categoryId = "22",
        onCategoryIdChanged = {},
        youtubeChannelTitle = channelTitle,
        youtubeAccountStatus = if (isChecking) "확인 중" else "연결 상태를 확인하지 못했습니다.",
        hasVerifiedYouTubeAccount = hasVerifiedAccount,
        isYouTubeReconnectRequired = false,
        isYouTubeAccountActionInProgress = isChecking,
        isYouTubeConnectEnabled = true,
        connectDisabledReasonRes = if (isChecking) R.string.youtube_account_action_in_progress else null,
        onConnectYouTube = onConnect,
        onSave = {},
        isSaveEnabled = false,
        saveDisabledReasonRes = R.string.broadcast_settings_connection_required,
        statusMessage = "",
    )
}
