package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.framework.innolive.R
import com.framework.innolive.feature.live.components.ServerErrorDialog
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.serverErrorGuidance
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServerErrorDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun concurrentBroadcastNeedsAnExplicitClickAndCancelDoesNotContinue() {
        val visible = mutableStateOf(true)
        var accepted = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) ServerErrorDialog(
                    guidance = serverErrorGuidance("channel_already_live")!!,
                    onDismiss = { visible.value = false },
                    onAction = { accepted++; visible.value = false },
                )
            }
        }
        compose.runOnIdle { assertEquals(0, accepted) }
        compose.onNodeWithText(compose.activity.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, accepted); visible.value = true }
        compose.onNodeWithText(compose.activity.getString(R.string.action_continue_broadcast)).performClick()
        compose.runOnIdle { assertEquals(1, accepted) }
        compose.onNodeWithText(compose.activity.getString(R.string.action_continue_broadcast)).assertDoesNotExist()
    }

    @Test fun fieldErrorIsInlineAndEditingClearsIt() {
        val settings = mutableStateOf(BroadcastSettings("", "", "private", false, "22"))
        val guidance = mutableStateOf<ServerErrorGuidance?>(serverErrorGuidance("bad_request", field = "title", reason = "required"))
        compose.setContent {
            MaterialTheme {
                YouTubeLiveSettingsDialog(
                    settings = settings.value,
                    youtubeChannelTitle = "fixture",
                    hasYouTubeAccount = true,
                    youtubeAccountStatus = "",
                    isYouTubeReconnectRequired = false,
                    isYouTubeAccountActionInProgress = false,
                    isYouTubeConnectEnabled = true,
                    onSettingsChanged = { settings.value = it; guidance.value = null },
                    onConnectYouTube = {},
                    onDismissRequest = {},
                    serverError = guidance.value,
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.error_field_required)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.label_broadcast_title)).performTextInput("valid title")
        compose.onNodeWithText(compose.activity.getString(R.string.error_field_required)).assertDoesNotExist()
    }

    @Test fun serverCapacityNoticeOnlyDismissesAndDoesNotRetry() {
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                ServerErrorDialog(serverErrorGuidance("capacity_exceeded")!!, onDismiss = { dismissed++ })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.action_retry)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.action_confirm)).performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}
