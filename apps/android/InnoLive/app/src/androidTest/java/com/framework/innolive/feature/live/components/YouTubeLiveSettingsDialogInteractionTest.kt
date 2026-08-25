package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.LiveScreenProps
import org.junit.Rule
import org.junit.Test

private object LiveSettingsRoute

class YouTubeLiveSettingsDialogInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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
        composeRule.onNodeWithText("Youtube").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("라이브 설정").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("라이브 설정").assertIsDisplayed()

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
            .onNode(hasText("공개 범위") and hasClickAction())
            .performClick()
        composeRule.onAllNodesWithText("공개").assertCountEquals(2)
        composeRule.onNodeWithText("일부 공개").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("state:ime-bound-input|privacy:unlisted|audience:null")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule
            .onNode(hasText("아동용 설정") and hasClickAction())
            .performClick()
        composeRule.onNodeWithText("아동용").assertIsDisplayed()
        composeRule.onNodeWithText("아동용 아님").assertIsDisplayed()
        composeRule.onAllNodesWithText("선택 필요").assertCountEquals(1)

        composeRule.onNodeWithText("아동용 아님").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("아동용 아님").fetchSemanticsNodes().size == 1
        }
        composeRule
            .onNodeWithText("state:ime-bound-input|privacy:unlisted|audience:false")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("선택 필요").assertCountEquals(0)
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
            .onNode(hasText("공개 범위") and hasClickAction())
            .performClick()
        composeRule.onNodeWithText("일부 공개").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("state:nav-entry-input|privacy:unlisted")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
