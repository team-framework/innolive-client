package com.framework.innolive.feature.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountDeletionSettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(@StringRes id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun confirmationIsRequiredBeforeRequestingAccountDeletion() {
        var deleteRequests = 0
        composeRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    SettingsScreenProps(
                        onBack = {},
                        onOpenCameraSettings = {},
                        onOpenBroadcastSettings = {},
                        profileName = "InnoLive User",
                        profileEmail = "user@example.com",
                        onLogout = {},
                        onDeleteAccount = { deleteRequests += 1 },
                    ),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.action_delete_account)).performClick()
        composeRule.onNodeWithText(string(R.string.delete_account_title)).assertIsDisplayed()
        assertEquals(0, deleteRequests)

        composeRule.onNodeWithContentDescription(
            string(R.string.content_description_confirm_delete_account),
        ).performClick()
        assertEquals(1, deleteRequests)
    }

    @Test
    fun localCleanupFailureOffersRetryAndDisablesLogout() {
        composeRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    SettingsScreenProps(
                        onBack = {},
                        onOpenCameraSettings = {},
                        onOpenBroadcastSettings = {},
                        profileName = "InnoLive User",
                        profileEmail = "user@example.com",
                        onLogout = {},
                        onDeleteAccount = {},
                        isAccountDeletionPending = true,
                        isAccountDeletionCleanupPending = true,
                        accountDeletionError =
                            UiText.Dynamic(
                                "서버 계정은 삭제됐지만 기기 데이터 정리에 실패했습니다. 다시 시도해 주세요.",
                            ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.action_retry_device_cleanup))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_logout)).assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.action_back)).assertIsNotEnabled()
        composeRule.onNodeWithText("서버 계정은 삭제됐지만 기기 데이터 정리에 실패했습니다. 다시 시도해 주세요.")
            .assertIsDisplayed()
    }
}
