package com.framework.innolive.feature.youtube

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class YouTubeVerificationMemoryTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun restoredScreenRequiresFreshServerVerificationBeforePreparing() {
        val account = StreamingAccount("youtube", "channel-123", "Creator", false)
        val restoration = StateRestorationTester(rule)
        lateinit var memory: YouTubeVerificationMemory
        restoration.setContent {
            memory = rememberYouTubeVerificationMemory()
            Text(
                if (hasVerifiedYouTubeAccount(
                        account,
                        memory.state.value,
                        memory.verifiedProfileEmail.value,
                        "viewer@example.com",
                    )) "ready" else "blocked",
            )
        }

        rule.runOnIdle {
            memory.state.value = YouTubeAccountVerificationState.VERIFIED
            memory.verifiedProfileEmail.value = "viewer@example.com"
            memory.suppressRefreshOnce.value = true
        }
        rule.onNodeWithText("ready").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("blocked").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(YouTubeAccountVerificationState.UNVERIFIED, memory.state.value)
            assertNull(memory.verifiedProfileEmail.value)
            assertFalse(memory.suppressRefreshOnce.value)
        }
    }
}
