package com.framework.innolive.feature.youtube

import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class YouTubeVerificationMemoryTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun failedReplacementDisablesPreparationUntilReplacementIsVerified() {
        val profileEmail = "viewer@example.com"
        val account = mutableStateOf(StreamingAccount("youtube", "old-channel", "Old Channel", false))
        val memory = YouTubeVerificationMemory().apply {
            state.value = YouTubeAccountVerificationState.VERIFIED
            verifiedProfileEmail.value = profileEmail
        }
        var preparations = 0
        rule.setContent {
            MaterialTheme {
                YouTubeLiveSettingsDialog(
                    settings = BroadcastSettings("Test broadcast", "Description", "private", false, "22"),
                    youtubeChannelTitle = account.value.channelTitle,
                    hasYouTubeAccount = hasVerifiedYouTubeAccount(
                        account.value, memory.state.value, memory.verifiedProfileEmail.value, profileEmail,
                    ),
                    youtubeAccountStatus = account.value.channelTitle,
                    isYouTubeReconnectRequired = false,
                    isYouTubeAccountActionInProgress = false,
                    isYouTubeConnectEnabled = true,
                    onSettingsChanged = {},
                    onConnectYouTube = {},
                    onDismissRequest = {},
                    onPrepare = { preparations++ },
                )
            }
        }
        val prepareLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.action_prepare_broadcast)
        val prepareButton = rule.onNodeWithText(prepareLabel)
        prepareButton.assertIsEnabled()

        rule.runOnIdle {
            assertThrows(IOException::class.java) {
                runBlocking {
                    memory.connectAccount { throw IOException("Replacement response lost") }
                }
            }
        }
        prepareButton.assertIsNotEnabled()
        rule.runOnIdle { memory.state.value = YouTubeAccountVerificationState.CHECKING }
        prepareButton.assertIsNotEnabled()
        // A failed account lookup cannot restore the stale verification.
        rule.runOnIdle { memory.state.value = YouTubeAccountVerificationState.UNVERIFIED }
        prepareButton.assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(0, preparations)
            account.value = StreamingAccount("youtube", "new-channel", "New Channel", false)
            memory.verifiedProfileEmail.value = profileEmail
            memory.state.value = YouTubeAccountVerificationState.VERIFIED
        }
        rule.onNodeWithText("New Channel").assertIsDisplayed()
        prepareButton.assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, preparations) }
    }

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
