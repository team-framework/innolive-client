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
import com.framework.innolive.feature.live.SessionSnapshot
import com.framework.innolive.feature.live.displayedRemainingTime
import com.framework.innolive.feature.live.rememberRemainingCountdown
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

    @Test fun sameSessionKeepsItsRemainingTimeAfterTheBroadcastEnds() {
        // 1회 12시간, 월 240시간 요금제. 세션 값이 없으면 12:00:00을 보여 준다.
        val usage = PlanUsage(
            plan = "pro", allowedModes = listOf("720p_single"), monthlySeconds = 864_000,
            maxBroadcastSeconds = 43_200, usedSeconds = 0, remainingSeconds = 864_000,
            modes = listOf(ModeAvailability("720p_single", true, 864_000, 1)),
        )
        var live by mutableStateOf(false)
        var snapshot by mutableStateOf<SessionSnapshot?>(null)
        composeRule.setContent {
            Box(Modifier.fillMaxWidth().background(Color.Black)) {
                // Live.kt와 같은 조합으로 화면 값을 만든다.
                BroadcastTimeDisplay(
                    uptime = if (live) "00:05:00" else "00:00:00",
                    remaining = rememberRemainingCountdown(
                        displayedRemainingTime(live, snapshot, usage),
                        counting = false,
                    ),
                    isStale = false,
                    onOpenPlan = {},
                )
            }
        }
        // 세션 생성 직후 서버 값이 오기 전: 요금제 기준 12시간
        composeRule.runOnIdle { snapshot = SessionSnapshot("s") }
        composeRule.onNodeWithText("12:00:00", useUnmergedTree = true).assertExists()
        // 5분 방송 중: 서버가 11:55:00을 보낸다.
        composeRule.runOnIdle {
            live = true
            snapshot = SessionSnapshot("s", remainingTime = BroadcastRemainingTime.Seconds(42_900))
        }
        composeRule.onNodeWithText("00:05:00", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("11:55:00", useUnmergedTree = true).assertExists()
        // 같은 세션에서 방송 종료: 방송 시간은 0으로, 남은 시간은 세션 값 그대로
        composeRule.runOnIdle { live = false }
        composeRule.onNodeWithText("00:00:00", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("11:55:00", useUnmergedTree = true).assertExists()
        saveScreenshot("broadcast-time-after-stop.png")
        // 새 세션: 서버가 다시 12시간을 보낸다.
        composeRule.runOnIdle { snapshot = SessionSnapshot("s2", remainingTime = BroadcastRemainingTime.Seconds(43_200)) }
        composeRule.onNodeWithText("12:00:00", useUnmergedTree = true).assertExists()
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
