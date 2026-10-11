package com.framework.innolive.feature.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.material3.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastRemainingTime
import com.framework.innolive.feature.live.BroadcastTimeDisplay
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlanUsageScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val usage = PlanUsage("beam", listOf("720p_single"), 432000, 28800, 3600, 428400,
        listOf(ModeAvailability("720p_single", true, 428400, 1), ModeAvailability("fhd_single", false, 214200, 2),
            ModeAvailability("720p_multi", false, 214200, 2), ModeAvailability("fhd_multi", false, 142800, 3)))

    @Test fun settingsDisplaysPlanAndLockedModesOpenGuidance() {
        composeRule.setContent { MyApplicationTheme { Surface { SettingsScreen(props().copy(planUsage = usage)) } } }
        composeRule.onNodeWithText(context.getString(R.string.plan_current, "Beam")).performScrollTo().assertExists()
        composeRule.onNodeWithText(context.getString(R.string.plan_hd_single)).performScrollTo().assertExists()
        saveScreenshot("plan-settings.png")
        composeRule.onNodeWithText(context.getString(R.string.plan_fhd_single)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.plan_mode_locked,
            context.getString(R.string.plan_fhd_single))).assertExists()
    }

    @Test fun failedRefreshPreservesLastValuesAndCanRetry() {
        composeRule.setContent {
            var error by remember { mutableStateOf<UiText?>(UiText.Resource(R.string.plan_load_failed)) }
            MyApplicationTheme { Surface { SettingsScreen(props().copy(planUsage = usage, planError = error,
                onRefreshPlan = { error = null })) } }
        }
        composeRule.onNodeWithText(context.getString(R.string.plan_load_failed)).performScrollTo().assertExists()
        saveScreenshot("plan-failed-refresh.png")
        composeRule.onNodeWithText(context.getString(R.string.plan_refresh)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.plan_load_failed)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.plan_current, "Beam")).assertExists()
    }

    @Test fun settingsCountdownTicksPausesAndResetsToNewServerUsage() {
        var charging by mutableStateOf(true)
        var snapshot by mutableStateOf(usage)
        var anchor by mutableStateOf(0L)
        var chargeMillis = 0L
        var displayed = usage
        composeRule.setContent {
            chargeMillis = rememberPlanChargeMillis("account", charging, 2)
            displayed = snapshot.afterChargedSeconds((chargeMillis - anchor).coerceAtLeast(0) / 1000)
            MyApplicationTheme { Surface { SettingsScreen(props().copy(planUsage = displayed)) } }
        }
        composeRule.waitUntil(5_000) { displayed.usedSeconds >= usage.usedSeconds + 2 }
        composeRule.onNodeWithText(context.getString(R.string.plan_remaining,
            formatPlanDuration(displayed.remainingSeconds!!))).performScrollTo().assertExists()
        composeRule.runOnIdle { charging = false }
        composeRule.waitForIdle()
        val frozen = displayed
        Thread.sleep(1_100)
        composeRule.mainClock.advanceTimeBy(1_100)
        composeRule.runOnIdle { assertEquals(frozen, displayed) }
        composeRule.runOnIdle {
            snapshot = usage.copy(usedSeconds = 4000, remainingSeconds = 428000)
            anchor = chargeMillis
        }
        composeRule.runOnIdle { assertEquals(4000L, displayed.usedSeconds) }
        composeRule.runOnIdle { charging = true }
        composeRule.waitUntil(5_000) { displayed.usedSeconds >= 4002 }
        composeRule.runOnIdle {
            assertTrue(displayed.remainingSeconds!! <= 427998)
            charging = false
        }
    }

    @Test fun broadcastTimeUpdatesSeparatelyAndOpensSettings() {
        var opens = 0
        lateinit var update: (BroadcastRemainingTime) -> Unit
        composeRule.setContent {
            var remaining by remember { mutableStateOf<BroadcastRemainingTime>(BroadcastRemainingTime.Seconds(720)) }
            update = { remaining = it }
            Box(Modifier.background(Color.Black).padding(16.dp)) {
                BroadcastTimeDisplay("00:01:23", remaining, false, { opens++ })
            }
        }
        composeRule.onNodeWithText("00:01:23").assertExists()
        composeRule.onNodeWithText("00:12:00").performClick()
        assertEquals(1, opens)
        saveScreenshot("broadcast-time.png")
        composeRule.runOnIdle { update(BroadcastRemainingTime.Seconds(59)) }
        composeRule.onNodeWithText("00:00:59").assertExists()
        composeRule.runOnIdle { update(BroadcastRemainingTime.UnlimitedOrInactive) }
        composeRule.onNodeWithText(context.getString(R.string.plan_unlimited)).assertExists()
    }

    @Test fun dividerSitsAtTheHorizontalCenterWithTimesOnEachSide() {
        lateinit var update: (BroadcastRemainingTime) -> Unit
        composeRule.setContent {
            var remaining by remember { mutableStateOf<BroadcastRemainingTime>(BroadcastRemainingTime.Seconds(43_200)) }
            update = { remaining = it }
            Box(Modifier.fillMaxWidth().background(Color.Black)) {
                BroadcastTimeDisplay("00:01:23", remaining, false, {}, Modifier.padding(horizontal = 16.dp))
            }
        }
        composeRule.onNodeWithText("12:00:00", useUnmergedTree = true).assertExists()
        assertSymmetricAroundCenter("12:00:00")
        saveScreenshot("broadcast-time-centered.png")
        composeRule.runOnIdle { update(BroadcastRemainingTime.Unknown) }
        assertSymmetricAroundCenter("—")
    }

    /** 방송 시간 오른쪽 끝과 남은 시간 왼쪽 끝이 화면 가운데에서 같은 거리에 있어야 한다. */
    private fun assertSymmetricAroundCenter(remainingText: String) {
        val rootWidth = composeRule.onRoot().getUnclippedBoundsInRoot().width
        val center = rootWidth / 2
        val uptime = composeRule.onNodeWithText("00:01:23", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val remaining = composeRule.onNodeWithText(remainingText, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val leftGap = center - uptime.right
        val rightGap = remaining.left - center
        assertTrue("left=$leftGap right=$rightGap", leftGap > 0.dp && rightGap > 0.dp)
        assertEquals(leftGap.value, rightGap.value, 1f)
    }

    @Test fun staleSessionKeepsRemainingValueWithVisibleStatusAndAccessibleLabels() {
        composeRule.setContent {
            Box(Modifier.background(Color.Black).padding(16.dp)) {
                BroadcastTimeDisplay("00:01:23", BroadcastRemainingTime.Seconds(720), true, {})
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.plan_session_stale)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.plan_remaining_description, "00:12:00")).assertExists()
        saveScreenshot("broadcast-time-stale.png")
    }

    private fun props() = SettingsScreenProps(onBack = {}, onOpenCameraSettings = {}, onOpenBroadcastSettings = {},
        profileName = "InnoLive", profileEmail = "test@example.com", onLogout = {})

    private fun saveScreenshot(name: String) {
        File(context.cacheDir, name).outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
