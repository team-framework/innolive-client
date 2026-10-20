package com.framework.innolive.feature.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun props(
        onOpenAccountSettings: () -> Unit = {},
        onLogout: () -> Unit = {},
        onDeleteAccount: () -> Unit = {},
        isAccountDeletionPending: Boolean = false,
        isAccountDeletionCleanupPending: Boolean = false,
        accountDeletionError: UiText? = null,
    ) = SettingsScreenProps(
        onBack = {},
        onOpenBroadcastSettings = {},
        profileName = "InnoLive User",
        profileEmail = "user@example.com",
        onLogout = onLogout,
        onOpenAccountSettings = onOpenAccountSettings,
        onDeleteAccount = onDeleteAccount,
        isAccountDeletionPending = isAccountDeletionPending,
        isAccountDeletionCleanupPending = isAccountDeletionCleanupPending,
        accountDeletionError = accountDeletionError,
    )

    @Test
    fun accountRowOpensAccountSettingsInsteadOfShowingAccountActions() {
        var accountSettingsRequests = 0
        composeRule.setContent {
            MyApplicationTheme {
                SettingsScreen(props(onOpenAccountSettings = { accountSettingsRequests += 1 }))
            }
        }

        composeRule.onNodeWithText(string(R.string.action_logout)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.action_delete_account)).assertDoesNotExist()
        composeRule.onNodeWithText("user@example.com").performClick()
        assertEquals(1, accountSettingsRequests)
    }

    @Test
    fun accountSettingsShowsLogoutAndAccountDeletion() {
        var logoutRequests = 0
        composeRule.setContent {
            MyApplicationTheme {
                AccountSettingsScreen(props(onLogout = { logoutRequests += 1 }), onBack = {})
            }
        }

        composeRule.onNodeWithText(string(R.string.settings_account)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_delete_account)).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.action_logout)).assertIsDisplayed().performClick()
        assertEquals(1, logoutRequests)
    }

    @Test
    fun confirmationIsRequiredBeforeRequestingAccountDeletion() {
        var deleteRequests = 0
        composeRule.setContent {
            MyApplicationTheme {
                AccountSettingsScreen(props(onDeleteAccount = { deleteRequests += 1 }), onBack = {})
            }
        }

        composeRule.onNodeWithText(string(R.string.action_delete_account)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.delete_account_title)).assertIsDisplayed()
        assertEquals(0, deleteRequests)

        composeRule.onNodeWithContentDescription(
            string(R.string.content_description_confirm_delete_account),
        ).performClick()
        assertEquals(1, deleteRequests)
    }

    @Test
    fun localCleanupFailureKeepsAccountRowAndDisablesClose() {
        var accountSettingsRequests = 0
        composeRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    props(
                        onOpenAccountSettings = { accountSettingsRequests += 1 },
                        isAccountDeletionPending = true,
                        isAccountDeletionCleanupPending = true,
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.action_close)).assertIsNotEnabled()
        composeRule.onNodeWithText("user@example.com").performClick()
        assertEquals(1, accountSettingsRequests)
    }

    @Test
    fun localCleanupFailureOffersRetryAndDisablesLogout() {
        composeRule.setContent {
            MyApplicationTheme {
                AccountSettingsScreen(
                    props(
                        isAccountDeletionPending = true,
                        isAccountDeletionCleanupPending = true,
                        accountDeletionError =
                            UiText.Dynamic(
                                "서버 계정은 삭제됐지만 기기 데이터 정리에 실패했습니다. 다시 시도해 주세요.",
                            ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.action_retry_device_cleanup))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_logout))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.action_back)).assertIsNotEnabled()
        composeRule.onNodeWithText("서버 계정은 삭제됐지만 기기 데이터 정리에 실패했습니다. 다시 시도해 주세요.")
            .performScrollTo().assertIsDisplayed()
    }
}
