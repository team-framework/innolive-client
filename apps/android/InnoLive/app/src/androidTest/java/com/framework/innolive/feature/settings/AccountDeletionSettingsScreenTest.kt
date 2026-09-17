package com.framework.innolive.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountDeletionSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
}
