package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.LiveScreenProps
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private object LiveSettingsRoute

class YouTubeLiveSettingsDialogInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(@StringRes resourceId: Int, vararg arguments: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId, *arguments)

    @Test
    fun cachedChannelNeverHidesAccountVerificationFailure() {
        composeRule.setContent {
            MaterialTheme {
                YouTubeLiveSettingsDialog(
                    settings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22"),
                    youtubeChannelTitle = "저장된 채널",
                    hasYouTubeAccount = false,
                    youtubeAccountStatus = text(R.string.youtube_status_check_failed),
                    isYouTubeReconnectRequired = false,
                    isYouTubeAccountActionInProgress = false,
                    isYouTubeConnectEnabled = true,
                    onSettingsChanged = {},
                    onConnectYouTube = {},
                    onDismissRequest = {},
                    onPrepare = {},
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.youtube_status_check_failed)).assertIsDisplayed()
        composeRule.onNodeWithText("저장된 채널").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.action_connect)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_prepare_broadcast)).assertIsNotEnabled()
    }

    @Test
    fun reconnectFailureReplacesPreviouslyVerifiedChannelLabel() {
        val status = mutableStateOf(text(R.string.youtube_status_channel, "검증 채널"))
        composeRule.setContent {
            MaterialTheme {
                YouTubeLiveSettingsDialog(
                    settings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22"),
                    youtubeChannelTitle = "검증 채널",
                    hasYouTubeAccount = true,
                    youtubeAccountStatus = status.value,
                    isYouTubeReconnectRequired = false,
                    isYouTubeAccountActionInProgress = false,
                    isYouTubeConnectEnabled = true,
                    onSettingsChanged = {},
                    onConnectYouTube = {},
                    onDismissRequest = {},
                    onPrepare = {},
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.youtube_status_channel, "검증 채널")).assertIsDisplayed()
        composeRule.runOnIdle { status.value = text(R.string.error_youtube_connection) }
        composeRule.onNodeWithText(text(R.string.error_youtube_connection))
            .assertIsDisplayed()
        composeRule.onNodeWithText("검증 채널").assertDoesNotExist()
    }

    @Test
    fun unlinkedAccountInformationUsesReadableRows() {
        composeRule.setContent {
            MaterialTheme {
                YouTubeLiveSettingsDialog(
                    settings = BroadcastSettings("검증 방송", "검증 설명", "private", false, "22"),
                    youtubeChannelTitle = null,
                    hasYouTubeAccount = false,
                    youtubeAccountStatus = text(R.string.youtube_status_no_account),
                    isYouTubeReconnectRequired = false,
                    isYouTubeAccountActionInProgress = false,
                    isYouTubeConnectEnabled = true,
                    onSettingsChanged = {},
                    onConnectYouTube = {},
                    onDismissRequest = {},
                )
            }
        }

        val heading = composeRule.onNodeWithText(text(R.string.label_account_information))
            .getUnclippedBoundsInRoot()
        val status = composeRule
            .onNodeWithText(text(R.string.youtube_status_no_account))
            .getUnclippedBoundsInRoot()

        assertTrue("계정 제목은 상태 문구보다 위에 있어야 합니다.", heading.bottom <= status.top)
    }

    @Test
    fun youtubeSelectionOpensInteractiveSettingsDialog() {
        val initialSettings = BroadcastSettings(
            title = "",
            description = "",
            privacy = "public",
            madeForKids = null,
            categoryId = "22",
        )

        composeRule.setContent {
            var openPlatformDialog by remember { mutableStateOf(false) }
            var pendingYouTubeSettingsDialog by remember { mutableStateOf(false) }
            var openYouTubeSettingsDialog by remember { mutableStateOf(false) }
            var settings by remember { mutableStateOf(initialSettings) }

            MaterialTheme {
                Column {
                    Button(onClick = { openPlatformDialog = true }) {
                        Text("방송 시작")
                    }
                    Text(
                        text = "state:${settings.title}|privacy:${settings.privacy}|audience:${settings.madeForKids}",
                    )
                }

                if (openPlatformDialog) {
                    PlatformDialog(
                        onDismissRequest = { openPlatformDialog = false },
                        onYouTubeSelected = {
                            pendingYouTubeSettingsDialog = true
                            openPlatformDialog = false
                        },
                    )
                }

                LaunchedEffect(openPlatformDialog, pendingYouTubeSettingsDialog) {
                    if (!openPlatformDialog && pendingYouTubeSettingsDialog) {
                        pendingYouTubeSettingsDialog = false
                        openYouTubeSettingsDialog = true
                    }
                }

                if (openYouTubeSettingsDialog) {
                    YouTubeLiveSettingsDialog(
                        settings = settings,
                        youtubeChannelTitle = null,
                        hasYouTubeAccount = false,
                        youtubeAccountStatus = "연결되지 않음",
                        isYouTubeReconnectRequired = false,
                        isYouTubeAccountActionInProgress = false,
                        isYouTubeConnectEnabled = true,
                        onSettingsChanged = { settings = it },
                        onConnectYouTube = {},
                        onDismissRequest = { openYouTubeSettingsDialog = false },
                    )
                }
            }
        }

        composeRule.onNodeWithText("방송 시작").performClick()
        composeRule.onNodeWithText("YouTube").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text(R.string.live_settings_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(R.string.live_settings_title)).assertIsDisplayed()

        val titleField = composeRule.onAllNodes(hasSetTextAction())[0].performClick()
        titleField.assertIsFocused()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input text ime-bound-input")
            .close()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("state:ime-bound-input|privacy:public|audience:null")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNodeWithText("state:ime-bound-input|privacy:public|audience:null")
            .assertIsDisplayed()

        composeRule
            .onNode(hasText(text(R.string.label_broadcast_privacy)) and hasClickAction())
            .performClick()
        composeRule.onAllNodesWithText(text(R.string.privacy_public)).assertCountEquals(2)
        composeRule.onNodeWithText(text(R.string.privacy_unlisted)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("state:ime-bound-input|privacy:unlisted|audience:null")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule
            .onNode(hasText(text(R.string.audience_required)) and hasClickAction())
            .performClick()
        composeRule
            .onNode(
                hasText(text(R.string.audience_made_for_kids)) and
                    !hasText(text(R.string.audience_required)) and hasClickAction(),
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.audience_not_made_for_kids)).assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.audience_required)).assertCountEquals(1)

        composeRule.onNodeWithText(text(R.string.audience_not_made_for_kids)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text(R.string.audience_not_made_for_kids))
                .fetchSemanticsNodes().size == 1
        }
        composeRule
            .onNodeWithText("state:ime-bound-input|privacy:unlisted|audience:false")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.audience_required)).assertCountEquals(0)
    }

    @Test
    fun saveShowsAudienceErrorUntilAudienceIsSelected() {
        val initialSettings = BroadcastSettings(
            title = "",
            description = "",
            privacy = "public",
            madeForKids = null,
            categoryId = "22",
        )

        composeRule.setContent {
            var settings by remember { mutableStateOf(initialSettings) }
            var isDialogOpen by remember { mutableStateOf(true) }

            MaterialTheme {
                if (isDialogOpen) {
                    YouTubeLiveSettingsDialog(
                        settings = settings,
                        youtubeChannelTitle = null,
                        hasYouTubeAccount = false,
                        youtubeAccountStatus = "연결되지 않음",
                        isYouTubeReconnectRequired = false,
                        isYouTubeAccountActionInProgress = false,
                        isYouTubeConnectEnabled = true,
                        onSettingsChanged = { settings = it },
                        onConnectYouTube = {},
                        onDismissRequest = { isDialogOpen = false },
                    )
                } else {
                    Text("dismissed")
                }
            }
        }

        composeRule.onNodeWithText(text(R.string.action_save_and_close)).performClick()
        composeRule.onNodeWithText(text(R.string.validation_broadcast_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.validation_broadcast_description)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.validation_audience)).assertIsDisplayed()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("방송 제목")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("방송 설명")
        composeRule.onAllNodesWithText(text(R.string.validation_broadcast_title)).assertCountEquals(0)
        composeRule.onAllNodesWithText(text(R.string.validation_broadcast_description)).assertCountEquals(0)

        composeRule
            .onNode(hasText(text(R.string.audience_required)) and hasClickAction())
            .performClick()
        composeRule
            .onNode(
                hasText(text(R.string.audience_made_for_kids)) and
                    !hasText(text(R.string.audience_required)) and hasClickAction(),
            )
            .performClick()
        composeRule.onAllNodesWithText(text(R.string.validation_audience)).assertCountEquals(0)

        composeRule.onNodeWithText(text(R.string.action_save_and_close)).performClick()
        composeRule.onNodeWithText("dismissed").assertIsDisplayed()
    }

    @Test
    fun settingsChangesAreReflectedThroughCachedNavEntry() {
        val initialSettings = BroadcastSettings(
            title = "",
            description = "",
            privacy = "public",
            madeForKids = null,
            categoryId = "22",
        )

        composeRule.setContent {
            var settings by remember { mutableStateOf(initialSettings) }
            val latestProps = rememberUpdatedState(
                LiveScreenProps(
                    cameraLensFacing = CameraLensFacing.BACK,
                    cameraResolution = null,
                    broadcastSettings = settings,
                    onBroadcastSettingsChanged = { settings = it },
                    youtubeChannelTitle = null,
                    hasYouTubeAccount = false,
                    youtubeAccountStatus = "연결되지 않음",
                    isYouTubeReconnectRequired = false,
                    isYouTubeAccountActionInProgress = false,
                    isYouTubeConnectEnabled = true,
                    onConnectYouTube = {},
                    onRefreshAccessToken = { "" },
                    onOpenSettings = {},
                ),
            )

            MaterialTheme {
                NavDisplay(
                    backStack = listOf(LiveSettingsRoute),
                    onBack = {},
                    entryProvider = { route ->
                        NavEntry(route) {
                            val props = latestProps.value
                            Text(
                                text = "state:${props.broadcastSettings.title}|privacy:${props.broadcastSettings.privacy}",
                            )
                            YouTubeLiveSettingsDialog(
                                settings = props.broadcastSettings,
                                youtubeChannelTitle = props.youtubeChannelTitle,
                                hasYouTubeAccount = props.hasYouTubeAccount,
                                youtubeAccountStatus = props.youtubeAccountStatus,
                                isYouTubeReconnectRequired = props.isYouTubeReconnectRequired,
                                isYouTubeAccountActionInProgress = props.isYouTubeAccountActionInProgress,
                                isYouTubeConnectEnabled = props.isYouTubeConnectEnabled,
                                onSettingsChanged = props.onBroadcastSettingsChanged,
                                onConnectYouTube = props.onConnectYouTube,
                                onDismissRequest = {},
                            )
                        }
                    },
                )
            }
        }

        val titleField = composeRule.onAllNodes(hasSetTextAction())[0].performClick()
        titleField.assertIsFocused()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input text nav-entry-input")
            .close()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("state:nav-entry-input|privacy:public")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule
            .onNode(hasText(text(R.string.label_broadcast_privacy)) and hasClickAction())
            .performClick()
        composeRule.onNodeWithText(text(R.string.privacy_unlisted)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("state:nav-entry-input|privacy:unlisted")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
