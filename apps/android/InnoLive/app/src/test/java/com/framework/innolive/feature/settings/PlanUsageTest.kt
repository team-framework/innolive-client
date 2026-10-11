package com.framework.innolive.feature.settings

import com.framework.innolive.feature.live.BroadcastRemainingTime
import com.framework.innolive.feature.live.SessionSnapshot
import com.framework.innolive.feature.live.RemainingCountdown
import com.framework.innolive.feature.live.formatRemainingSeconds
import com.framework.innolive.feature.live.displayedRemainingTime
import com.framework.innolive.feature.live.parseSessionSnapshot
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.ui.text.serverErrorGuidance
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlanUsageTest {
    private val plan = """{"plan":"beam","allowed_modes":["720p_single","fhd_single"],"monthly_broadcast_seconds":432000,"max_per_broadcast_seconds":3600}"""
    private fun fixture(name: String): String {
        val root = generateSequence(File(checkNotNull(System.getProperty("innolive.android.resDir")))) { it.parentFile }
            .first { File(it, "contracts/fixtures").isDirectory }
        return File(root, "contracts/fixtures/plan-usage-$name.v1.json").readText()
    }

    @Test fun sharedFixturesDistinguishAllowedLockedExhaustedAndUnlimited() {
        val allowed = parsePlanUsage(plan, fixture("allowed"))
        assertTrue(allowed.isAllowed(PlanMode.HD_SINGLE))
        assertEquals(1L, allowed.availability(PlanMode.HD_SINGLE)!!.multiplier)
        assertEquals(3600L, allowed.previewRemaining(PlanMode.HD_SINGLE))
        assertFalse(parsePlanUsage(plan, fixture("locked")).isAllowed(PlanMode.HD_SINGLE))
        assertEquals(0L, parsePlanUsage(plan, fixture("exhausted")).previewRemaining(PlanMode.HD_SINGLE))
        val unlimited = parsePlanUsage(plan, fixture("unlimited"))
        assertNull(unlimited.remainingSeconds)
        assertEquals(3600L, unlimited.previewRemaining(PlanMode.HD_SINGLE))
        assertNull(unlimited.copy(maxBroadcastSeconds = 0).previewRemaining(PlanMode.HD_SINGLE))
        assertFalse(allowed.isAllowed(PlanMode.FHD_SINGLE))
        assertFalse(allowed.copy(allowedModes = emptyList()).isAllowed(PlanMode.HD_SINGLE))
    }

    @Test fun malformedAndMissingTimesCannotBecomeUnlimited() {
        val usage = fixture("allowed")
        for (invalid in listOf("-1", "1.5", "\"7200\"", "true", "9223372036854775808")) {
            assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("\"remaining_seconds\": 7200", "\"remaining_seconds\": $invalid")) }
        }
        assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("\"remaining_seconds\": 7200,", "")) }
        assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("\"seconds\": 7200,", "")) }
        assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("\"multiplier\": 1", "\"multiplier\": 0")) }
        assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("beam", "spark")) }
    }

    @Test fun startupNullUsesFinitePlanUntilSessionTimeArrives() {
        val usage = parsePlanUsage(plan, fixture("allowed"))
        assertEquals(BroadcastRemainingTime.Seconds(3600), displayedRemainingTime(false, null, usage))
        assertEquals(BroadcastRemainingTime.Seconds(3600), displayedRemainingTime(true, null, usage))
        val session = SessionSnapshot("s", remainingTime = BroadcastRemainingTime.Seconds(720))
        assertEquals(BroadcastRemainingTime.Seconds(720), displayedRemainingTime(true, session, usage))
        // 같은 세션에서 방송을 끝낸 뒤에도 요금제 기준(3600)이 아니라 그 세션의 남은 시간을 보여 준다.
        assertEquals(BroadcastRemainingTime.Seconds(720), displayedRemainingTime(false, session, usage))
        assertEquals(BroadcastRemainingTime.Seconds(720),
            displayedRemainingTime(false, session, usage.copy(allowedModes = emptyList())))
        // 세션은 있지만 서버 값이 아직 없으면 요금제 기준 값으로 보완한다.
        assertEquals(BroadcastRemainingTime.Seconds(3600),
            displayedRemainingTime(false, session.copy(remainingTime = BroadcastRemainingTime.Unknown), usage))
        assertEquals(BroadcastRemainingTime.Seconds(3600),
            displayedRemainingTime(true, session.copy(remainingTime = BroadcastRemainingTime.UnlimitedOrInactive), usage))
        val unlimited = parsePlanUsage(plan, fixture("unlimited")).copy(maxBroadcastSeconds = 0)
        assertEquals(BroadcastRemainingTime.UnlimitedOrInactive,
            displayedRemainingTime(true, session.copy(remainingTime = BroadcastRemainingTime.UnlimitedOrInactive), unlimited))
        assertEquals(BroadcastRemainingTime.Unknown, displayedRemainingTime(true, null, unlimited))
        assertEquals(BroadcastRemainingTime.Unknown, displayedRemainingTime(false, null, usage.copy(allowedModes = emptyList())))
    }

    @Test fun countdownUsesChargedUnitsAndPreservesCapsUnlimitedAndExhaustion() {
        val usage = parsePlanUsage(plan, fixture("allowed")).copy(modes = listOf(
            ModeAvailability("720p_single", true, 7200, 1),
            ModeAvailability("fhd_single", true, 3600, 2),
            ModeAvailability("fhd_multi", false, 2400, 3),
        ))
        val ticked = usage.afterChargedSeconds(3)
        assertEquals(usage.usedSeconds + 3, ticked.usedSeconds)
        assertEquals(7197L, ticked.remainingSeconds)
        assertEquals(listOf(7197L, 3598L, 2399L), ticked.modes.map { it.seconds })
        assertEquals(usage.monthlySeconds, ticked.monthlySeconds)
        assertEquals(usage.maxBroadcastSeconds, ticked.maxBroadcastSeconds)
        assertEquals(usage, usage.afterChargedSeconds(0))
        assertEquals(usage, usage.afterChargedSeconds(-1))
        assertEquals(0L, usage.afterChargedSeconds(Long.MAX_VALUE).remainingSeconds)
        assertEquals(Long.MAX_VALUE, usage.afterChargedSeconds(Long.MAX_VALUE).usedSeconds)
        assertTrue(usage.afterChargedSeconds(Long.MAX_VALUE).modes.all { it.seconds == 0L })
        val unlimited = parsePlanUsage(plan, fixture("unlimited")).afterChargedSeconds(2)
        assertNull(unlimited.remainingSeconds)
        assertTrue(unlimited.modes.all { it.seconds == null })
    }

    @Test fun snapshotRetainsResolutionAndStaleValueUntilRemainingTimeIsConfirmed() {
        val stale = SessionSnapshot("s", remainingTime = BroadcastRemainingTime.Seconds(720),
            broadcastResolution = "fhd", isRemainingTimeStale = true)
        assertEquals(stale, parseSessionSnapshot("{}", "s", stale))
        val fresh = parseSessionSnapshot("""{"broadcast_remaining_seconds":600,"broadcast_resolution":"720p"}""", "s", stale)
        assertFalse(fresh.isRemainingTimeStale)
        assertEquals("720p", fresh.broadcastResolution)
        assertEquals(BroadcastRemainingTime.Seconds(600), fresh.remainingTime)
    }

    @Test fun liveRemainingTimeCountsDownBetweenServerUpdates() {
        val live = RemainingCountdown(serverSeconds = 720, baseAtMillis = 0).onCountingChanged(true, 0)

        assertEquals(720L, live.displayed(0))
        assertEquals(719L, live.displayed(1_000))
        assertEquals(706L, live.displayed(14_999))
        // 같은 서버 값이 다시 와도 처음 받은 시각 기준을 유지한다.
        assertEquals(706L, live.onServerValue(720, 14_999).displayed(14_999))
        // 새 서버 값이 오면 그 값에서 다시 줄인다.
        val corrected = live.onServerValue(705, 15_000)
        assertEquals(705L, corrected.displayed(15_000))
        assertEquals(704L, corrected.displayed(16_000))
        // 갱신이 오래 없으면 30초 이상 줄이지 않고, 0 아래로 내려가지 않는다.
        assertEquals(690L, live.displayed(120_000))
        assertEquals(0L, RemainingCountdown(3, baseAtMillis = 0).onCountingChanged(true, 0).displayed(10_000))
    }

    @Test fun pauseFreezesTheShownValueAndResumeContinuesFromIt() {
        val live = RemainingCountdown(serverSeconds = 720, baseAtMillis = 0).onCountingChanged(true, 0)

        // 10초 뒤 일시 중지하면 서버 값(720)으로 되돌아가지 않고 보이던 710에서 멈춘다.
        val paused = live.onCountingChanged(false, 10_000)
        assertEquals(710L, paused.displayed(10_000))
        assertEquals(710L, paused.displayed(60_000))
        // 50초 뒤 재개하면 멈춘 동안의 시간을 한꺼번에 빼지 않고 710에서 다시 줄인다.
        val resumed = paused.onCountingChanged(true, 60_000)
        assertEquals(710L, resumed.displayed(60_000))
        assertEquals(709L, resumed.displayed(61_000))
    }

    @Test fun modeAndTimeFormattingReflectTheServerValues() {
        assertEquals(PlanMode.HD_SINGLE, PlanMode.current(null, 0))
        assertEquals(PlanMode.FHD_SINGLE, PlanMode.current("fhd", 1))
        assertEquals(PlanMode.HD_MULTI, PlanMode.current("720p", 2))
        assertEquals(PlanMode.FHD_MULTI, PlanMode.current("fhd", 2))
        assertEquals("00:12:00", formatRemainingSeconds(720))
        assertEquals("00:00:59", formatRemainingSeconds(59))
        assertEquals("00:00:00", formatRemainingSeconds(0))
        assertEquals("12:00:00", formatRemainingSeconds(43_200))
        assertEquals("100:00:00", formatRemainingSeconds(360_000))
        assertEquals("01:01:01", formatPlanDuration(3661))
        assertEquals("00:00:00", formatPlanDuration(0))
        assertEquals(ServerErrorAction.PLAN, serverErrorGuidance("monthly_limit_exhausted")!!.action)
    }
}
