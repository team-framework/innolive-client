package com.framework.innolive.feature.live.tutorial

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.SessionTarget
import com.framework.innolive.feature.live.components.VerticalHeroButton
import com.framework.innolive.feature.live.components.YouTubeLiveSettingsDialog
import com.framework.innolive.feature.live.status.BroadcastLiveStatusPanel
import com.framework.innolive.feature.live.status.BroadcastUplinkLimitation
import com.framework.innolive.feature.live.status.BroadcastUplinkQuality
import com.framework.innolive.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class BroadcastGuideUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class MemoryStore : BroadcastTutorialStore {
        override var hasFinishedPreparationGuide = false
        override var hasSeenLiveStatusTip = false
    }

    @Test
    fun homeGuideLetsOnlyTheHighlightedButtonAndCalloutThrough() {
        val tutorial = BroadcastTutorialCoordinator(MemoryStore())
        var primaryClicks = 0
        var outsideClicks = 0
        compose.setContent {
            HomeFixture(
                tutorial = tutorial,
                onPrimary = { primaryClicks++ },
                onOutside = { outsideClicks++ },
            )
        }
        compose.runOnIdle { tutorial.startIfNeeded(BroadcastTutorialSnapshot()) }

        compose.onNodeWithText(string(R.string.tutorial_open_preparation_title)).assertIsDisplayed()
        compose.onNodeWithText("1/5").assertIsDisplayed()
        saveScreenshot("home-open-preparation")

        compose.onNodeWithText(OUTSIDE_BUTTON).performClick()
        compose.onNodeWithText(PRIMARY_BUTTON).performClick()
        compose.runOnIdle {
            assertEquals(0, outsideClicks)
            assertEquals(1, primaryClicks)
        }

        compose.onNodeWithText(string(R.string.tutorial_skip)).performClick()
        compose.runOnIdle { assertNull(tutorial.stage) }
        compose.onNodeWithText(OUTSIDE_BUTTON).performClick()
        compose.runOnIdle { assertEquals(1, outsideClicks) }
    }

    @Test
    fun goLiveStepFinishesWithGotIt() {
        val store = MemoryStore()
        val tutorial = BroadcastTutorialCoordinator(store)
        compose.setContent { HomeFixture(tutorial = tutorial) }
        compose.runOnIdle {
            tutorial.restart(BroadcastTutorialSnapshot(selectedAccountConnected = true, broadcastState = BroadcastState.PREPARED))
        }

        compose.onNodeWithText(string(R.string.tutorial_go_live_title)).assertIsDisplayed()
        saveScreenshot("home-go-live")
        compose.onNodeWithText(string(R.string.tutorial_got_it)).performClick()

        compose.runOnIdle {
            assertNull(tutorial.stage)
            assertEquals(true, store.hasFinishedPreparationGuide)
        }
    }

    @Test
    fun liveStatusTipPointsAtThePanelWithoutBlockingControls() {
        val store = MemoryStore().apply { hasFinishedPreparationGuide = true }
        val tutorial = BroadcastTutorialCoordinator(store)
        var outsideClicks = 0
        compose.setContent {
            HomeFixture(tutorial = tutorial, showsStatusPanel = true, onOutside = { outsideClicks++ })
        }
        compose.runOnIdle {
            tutorial.update(
                BroadcastTutorialSnapshot(
                    selectedAccountConnected = true,
                    broadcastState = BroadcastState.LIVE,
                    hasStartedBroadcast = true,
                ),
            )
        }

        compose.onNodeWithText(string(R.string.tutorial_live_status_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.live_status_warning_network)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.live_status_uplink, "720p · 30fps"))
            .assertIsDisplayed()
        saveScreenshot("home-live-status")

        compose.onNodeWithText(OUTSIDE_BUTTON).performClick()
        compose.runOnIdle { assertEquals(1, outsideClicks) }
        compose.onNodeWithText(string(R.string.tutorial_got_it)).performClick()
        compose.runOnIdle { assertEquals(true, store.hasSeenLiveStatusTip) }
    }

    @Test
    fun settingsDialogPinsTheGuideAboveTheForm() {
        val tutorial = BroadcastTutorialCoordinator(MemoryStore())
        compose.setContent {
            MyApplicationTheme {
                YouTubeLiveSettingsDialog(
                    settings = BroadcastSettings("", "", "public", null, "22"),
                    youtubeChannelTitle = null,
                    hasYouTubeAccount = false,
                    youtubeAccountStatus = "YouTube 계정을 연결하세요.",
                    isYouTubeReconnectRequired = false,
                    isYouTubeAccountActionInProgress = false,
                    isYouTubeConnectEnabled = true,
                    onSettingsChanged = {},
                    onConnectYouTube = {},
                    onDismissRequest = {},
                    onPrepare = {},
                    guide = tutorial.guideFor(BroadcastTutorialHost.SETTINGS_DIALOG),
                )
            }
        }
        compose.runOnIdle {
            tutorial.restart(BroadcastTutorialSnapshot(openDialog = BroadcastTutorialDialog.SETTINGS))
        }

        compose.onNodeWithText(string(R.string.tutorial_connect_account_title)).assertIsDisplayed()
        compose.onNodeWithText("2/5").assertIsDisplayed()
        saveScreenshot("settings-connect-account")
    }

    @androidx.compose.runtime.Composable
    private fun HomeFixture(
        tutorial: BroadcastTutorialCoordinator,
        showsStatusPanel: Boolean = false,
        onPrimary: () -> Unit = {},
        onOutside: () -> Unit = {},
    ) {
        val anchors = remember { BroadcastTutorialAnchors() }
        MyApplicationTheme {
            CompositionLocalProvider(LocalBroadcastTutorialAnchors provides anchors) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF30343A)),
                ) {
                    TextButton(onClick = onOutside, modifier = Modifier.align(Alignment.TopStart).padding(top = 48.dp)) {
                        Text(OUTSIDE_BUTTON, color = Color.White)
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (showsStatusPanel) {
                            BroadcastLiveStatusPanel(
                                targets = listOf(
                                    SessionTarget("youtube", "streaming", "live", null, 0),
                                    SessionTarget("chzzk", "reconnecting", "live", null, 1),
                                ),
                                broadcastResolution = "1080P",
                                uplinkQuality = BroadcastUplinkQuality(720, 30, BroadcastUplinkLimitation.NETWORK),
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .widthIn(max = 360.dp)
                                    .fillMaxWidth()
                                    .broadcastTutorialAnchor(BroadcastTutorialAnchor.LIVE_STATUS),
                            )
                        }
                        VerticalHeroButton(
                            text = PRIMARY_BUTTON,
                            onClick = onPrimary,
                            modifier = Modifier.broadcastTutorialAnchor(BroadcastTutorialAnchor.PRIMARY_BUTTON),
                        )
                    }
                    BroadcastTutorialHomeOverlay(tutorial = tutorial, anchors = anchors, isEnabled = true)
                }
            }
        }
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    /** 렌더링 결과를 기기 외부 앱 폴더에 남겨 사람이 확인할 수 있게 한다. */
    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "broadcast-guide").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val PRIMARY_BUTTON = "방송 준비"
        const val OUTSIDE_BUTTON = "다른 버튼"
    }
}
