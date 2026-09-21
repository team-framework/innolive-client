package com.framework.innolive.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountDeletionSettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

        composeRule.onNodeWithText("계정 삭제").performClick()
        composeRule.onNodeWithText("계정을 삭제할까요?").assertIsDisplayed()
        assertEquals(0, deleteRequests)

        composeRule.onNodeWithContentDescription("계정 삭제 확인").performClick()
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
                            "서버 계정은 삭제됐지만 기기 데이터 정리에 실패했습니다. 다시 시도해 주세요.",
                    ),
                )
            }
        }

        composeRule.onNodeWithText("기기 데이터 정리 다시 시도").assertIsDisplayed()
        composeRule.onNodeWithText("로그아웃").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("뒤로가기").assertIsNotEnabled()
        composeRule.onNodeWithText("서버 계정은 삭제됐지만 기기 데이터 정리에 실패했습니다. 다시 시도해 주세요.")
            .assertIsDisplayed()
    }
}
